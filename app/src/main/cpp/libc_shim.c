/*
 * webhtv_shim — bionic compatibility shim for 32-bit Android 6 (API 23).
 *
 * WHY THIS EXISTS
 * ---------------
 * Chaquopy 17's CPython is built for android-24. Every one of its native modules is
 * therefore allowed to reference bionic symbols that only exist from Android 7.0
 * (API 24) onwards, and those references carry the version node LIBC_N. Android 6's
 * libc.so has no LIBC_N node at all, and bionic resolves with RTLD_NOW, so a single
 * unresolvable symbol makes the whole dlopen() fail:
 *
 *     dlopen failed: cannot locate symbol "lockf64" referenced by ".../base.apk"
 *
 * Two groups of symbols are affected, found by auditing every native module Chaquopy
 * ships (CPython stdlib + bootstrap-native + the app's pip requirements) against the
 * NDK's API-23 stub libraries:
 *
 *   1) libpython3.10.so itself, 32-bit ABI only. Built with _FILE_OFFSET_BITS=64,
 *      which on a 32-bit ABI redirects these to their 64-bit variants:
 *
 *          lockf64      preadv64      pwritev64
 *
 *      Not an arm64 problem: there off_t is already 64-bit, so CPython calls
 *      lockf()/preadv() directly and needs none of the *64 variants. That is exactly
 *      why the arm64 phone worked and the 32-bit TV did not.
 *
 *   2) CPython's own stdlib extension modules, both ABIs:
 *
 *          _socket.cpython-310.so    ->  if_nameindex, if_freenameindex
 *          resource.cpython-310.so   ->  prlimit            (32-bit only)
 *
 *      These modules are dlopen'ed lazily by the interpreter, so group 2 only bites
 *      once Python starts importing: `import socket` fails, which cascades into
 *      urllib3 -> requests -> the app's own spider module. The symptom is a
 *      PyException wrapping `cannot locate symbol "if_nameindex"`, and it happens on
 *      arm64 too. prlimit is @LIBC (API 21) on LP64, hence 32-bit only.
 *
 * Because the first failure happened inside BaseLoader's static initialiser, it used
 * to take the whole config loader down with it.
 *
 * WHAT THIS DOES
 * --------------
 * Implements all of them on top of primitives Android 6 does have (fcntl, lseek64,
 * readv, writev, prlimit64, SIOCGIFCONF) and exports them under the LIBC_N version
 * node via libc_shim.map, so the versioned references resolve. The list is complete
 * for the APK as built; if a future Chaquopy upgrade adds native modules, re-run
 * apk-check/audit_sym_versions.py rather than guessing.
 *
 * Load order matters: this library must be loaded *before* Chaquopy's. That is done in
 * chaquo/src/main/java/com/fongmi/chaquo/Platform.java, immediately before its
 * loadNativeLibs() loop.
 *
 * Load order alone is NOT enough, though. libpython3.10.so does not list us in its
 * DT_NEEDED, so it can only see our symbols if we are in the *global* scope when it is
 * relocated — and a plain dlopen() defaults to RTLD_LOCAL. The library therefore marks
 * itself DF_1_GLOBAL (see -Wl,-z,global in CMakeLists.txt), which bionic honours on
 * load regardless of the flags the caller passed.
 *
 * DELIBERATE LIMITATIONS
 * ----------------------
 * - lockf64 only supports offsets/lengths that fit in 32 bits, because it is built on
 *   the 32-bit fcntl path. Bigger regions get EOVERFLOW rather than a wrong answer.
 * - preadv64/pwritev64 are emulated with lseek64 + readv/writev, so they are neither
 *   atomic nor thread-safe with respect to other I/O on the same fd. CPython only
 *   reaches them via os.preadv()/os.pwritev(), which nothing in this project uses.
 *   A load-time failure would be far worse than this trade-off.
 * - if_nameindex() is built on the SIOCGIFCONF ioctl rather than getifaddrs(), because
 *   getifaddrs() is itself API 24. SIOCGIFCONF reports one entry per *address*, so
 *   interfaces with several addresses (or aliases) are de-duplicated by name.
 * - prlimit() narrows the 64-bit result back to the 32-bit struct rlimit. A limit
 *   above 4 GiB would be reported as truncated; no limit Android 6 sets by default
 *   comes close, and CPython only exposes this through resource.prlimit().
 * - If a future CPython starts using these on a hot path, revisit this file.
 *
 * Kept as C (not C++) so it stays independent of the C++ runtime.
 */

#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <net/if.h>          /* struct if_nameindex, struct ifreq/ifconf, IFNAMSIZ */
#include <linux/sockios.h>   /* SIOCGIFCONF, SIOCGIFINDEX */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <unistd.h>

#if !defined(__BIONIC__)
#error "This shim is only meaningful against Android's bionic libc."
#endif

/* net/if.h only declares these two from API 24 onwards, and we build at API 23, so
 * declare them here. The struct if_nameindex definition itself is unconditional. */
struct if_nameindex *if_nameindex(void);
void if_freenameindex(struct if_nameindex *ptr);

/* ------------------------------------------------------------------ */
/* lockf64 — POSIX record locking, 64-bit length variant               */
/* ------------------------------------------------------------------ */

int lockf64(int fd, int cmd, off64_t len) {
    struct flock fl;
    off64_t start = lseek64(fd, 0, SEEK_CUR);
    int op;
    int rc;

    if (start == (off64_t) -1) return -1;

#if !defined(__LP64__)
    /* lockf() locks from the current file offset. On a 32-bit ABI the fcntl path we
     * build on cannot express anything larger, so refuse rather than silently locking
     * the wrong region. 64-bit ABIs use the 64-bit flock layout and have nothing to
     * truncate. */
    if (start > (off64_t) INT32_MAX || len > (off64_t) INT32_MAX) {
        errno = EOVERFLOW;
        return -1;
    }
#endif

    memset(&fl, 0, sizeof(fl));
    fl.l_whence = SEEK_SET;
    fl.l_start = (off_t) start;
    fl.l_len = (off_t) len;

    switch (cmd) {
        case F_ULOCK:
            fl.l_type = F_UNLCK;
            op = F_SETLK;
            break;
        case F_LOCK:
            fl.l_type = F_WRLCK;
            op = F_SETLKW; /* blocking, as POSIX requires */
            break;
        case F_TLOCK:
            fl.l_type = F_WRLCK;
            op = F_SETLK;
            break;
        case F_TEST:
            fl.l_type = F_WRLCK;
            op = F_GETLK;
            break;
        default:
            errno = EINVAL;
            return -1;
    }

    do {
        rc = fcntl(fd, op, &fl);
    } while (rc == -1 && errno == EINTR);

    /* F_GETLK reports a conflicting lock in fl.l_type instead of failing. */
    if (rc == 0 && cmd == F_TEST && fl.l_type != F_UNLCK) {
        errno = EACCES;
        return -1;
    }

    return rc;
}

/* ------------------------------------------------------------------ */
/* preadv64 / pwritev64 — vectored I/O at an absolute offset            */
/* ------------------------------------------------------------------ */

static ssize_t shim_iovec_at(int fd, const struct iovec *iov, int iovcnt,
                             off64_t offset, int writing) {
    off64_t saved = lseek64(fd, 0, SEEK_CUR);
    ssize_t rc;
    int saved_errno;

    if (saved == (off64_t) -1) return -1;
    if (lseek64(fd, offset, SEEK_SET) == (off64_t) -1) return -1;

    do {
        rc = writing ? writev(fd, iov, iovcnt) : readv(fd, iov, iovcnt);
    } while (rc == -1 && errno == EINTR);

    saved_errno = errno;

    /* Restore the file position no matter how the transfer went, and keep the
     * transfer's errno if it failed. */
    if (lseek64(fd, saved, SEEK_SET) == (off64_t) -1 && rc != -1) {
        return -1;
    }

    errno = saved_errno;
    return rc;
}

ssize_t preadv64(int fd, const struct iovec *iov, int iovcnt, off64_t offset) {
    return shim_iovec_at(fd, iov, iovcnt, offset, 0);
}

ssize_t pwritev64(int fd, const struct iovec *iov, int iovcnt, off64_t offset) {
    return shim_iovec_at(fd, iov, iovcnt, offset, 1);
}

/* ------------------------------------------------------------------ */
/* if_nameindex / if_freenameindex — enumerate interfaces (API 24)      */
/* ------------------------------------------------------------------ */

/*
 * Needed by _socket.cpython-310.so on BOTH ABIs: CPython's socket.if_nameindex()
 * calls straight into libc. Without these, `import socket` itself fails to load,
 * which takes urllib3 -> requests -> the app's spiders down with it.
 *
 * getifaddrs()/freeifaddrs() would be the natural basis, but they are themselves
 * API 24 (@LIBC_N), so fall back to the classic SIOCGIFCONF ioctl, which has existed
 * since API 1.
 */
struct if_nameindex *if_nameindex(void) {
    int fd;
    struct ifconf ifc;
    char buf[4096];
    struct if_nameindex *out;
    int count, kept = 0, i;

    fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return NULL;

    memset(&ifc, 0, sizeof(ifc));
    ifc.ifc_len = (int) sizeof(buf);
    ifc.ifc_buf = buf;

    if (ioctl(fd, SIOCGIFCONF, &ifc) < 0) {
        close(fd);
        return NULL;
    }

    count = (int) (ifc.ifc_len / (int) sizeof(struct ifreq));

    /* Terminated by one extra all-zero entry, hence count + 1. */
    out = (struct if_nameindex *) calloc((size_t) count + 1, sizeof(*out));
    if (out == NULL) {
        close(fd);
        return NULL;
    }

    for (i = 0; i < count; i++) {
        struct ifreq req;
        char *name;
        int j, duplicate = 0;

        memset(&req, 0, sizeof(req));
        strncpy(req.ifr_name, ifc.ifc_req[i].ifr_name, IFNAMSIZ - 1);
        req.ifr_name[IFNAMSIZ - 1] = '\0';

        /* SIOCGIFCONF reports one entry per address, so an interface with several
         * addresses (or an alias) shows up more than once. if_nameindex() must list
         * each interface exactly once, so drop repeats. */
        for (j = 0; j < kept; j++) {
            if (strcmp(out[j].if_name, req.ifr_name) == 0) {
                duplicate = 1;
                break;
            }
        }
        if (duplicate) continue;

        if (ioctl(fd, SIOCGIFINDEX, &req) < 0) continue;

        name = strdup(req.ifr_name);
        if (name == NULL) break;

        out[kept].if_index = (unsigned) req.ifr_ifindex;
        out[kept].if_name = name;
        kept++;
    }

    /* The array is terminated by an entry whose if_name is NULL. calloc() already
     * zeroed it, so this is belt and braces. */
    out[kept].if_index = 0;
    out[kept].if_name = NULL;

    close(fd);
    return out;
}

void if_freenameindex(struct if_nameindex *ptr) {
    struct if_nameindex *p;

    if (ptr == NULL) return;
    for (p = ptr; p->if_name != NULL; p++) free(p->if_name);
    free(ptr);
}

/* ------------------------------------------------------------------ */
/* prlimit — get/set resource limits for an arbitrary process (API 24)  */
/* ------------------------------------------------------------------ */

/*
 * Needed by resource.cpython-310.so on the 32-bit ABI only; on LP64 bionic has
 * exported prlimit since API 21.
 *
 * prlimit64() has been available since API 21 on every ABI, so this is a pure
 * widening wrapper: the 32-bit struct rlimit (rlim_t = unsigned long) is promoted to
 * struct rlimit64 (rlim64_t = unsigned long long) and the result is narrowed back.
 */
int prlimit(pid_t pid, int resource, const struct rlimit *new_limit,
            struct rlimit *old_limit) {
    struct rlimit64 new64, old64;
    struct rlimit64 *newp = NULL;
    struct rlimit64 *oldp = NULL;
    int rc;

    if (new_limit != NULL) {
        new64.rlim_cur = (rlim64_t) new_limit->rlim_cur;
        new64.rlim_max = (rlim64_t) new_limit->rlim_max;
        newp = &new64;
    }
    if (old_limit != NULL) {
        oldp = &old64;
    }

    rc = prlimit64(pid, resource, newp, oldp);
    if (rc != 0) return rc;

    if (old_limit != NULL) {
        old_limit->rlim_cur = (rlim_t) old64.rlim_cur;
        old_limit->rlim_max = (rlim_t) old64.rlim_max;
    }
    return 0;
}
