/*
 * webhtv_shim — bionic compatibility shim for 32-bit Android 6 (API 23).
 *
 * WHY THIS EXISTS
 * ---------------
 * This fork exists to run on Android 6, but several prebuilt native libraries in the
 * APK were compiled against android-24 (or later) headers. They are therefore allowed
 * to reference bionic symbols that only exist from Android 7.0 (API 24) onwards, and
 * those references carry the version node LIBC_N. Android 6's libc.so has no LIBC_N
 * node at all, and bionic resolves with RTLD_NOW, so a single unresolvable symbol
 * makes the whole dlopen() fail:
 *
 *     dlopen failed: cannot locate symbol "lockf64" referenced by ".../base.apk"
 *
 * The affected symbols were found by auditing every native library in the APK against
 * the NDK's API-23 stub libraries (apk-check/audit_sym_versions.py). Three groups:
 *
 *   1) libpython3.10.so, both ABIs, under two different sets of names:
 *
 *          armeabi-v7a    lockf64      preadv64      pwritev64
 *          arm64-v8a      lockf        preadv        pwritev
 *
 *      All six are API 24 additions, and Android 6's libc exports none of them. Which
 *      name a given ABI carries is decided by _FILE_OFFSET_BITS=64: on a 32-bit ABI it
 *      redirects lockf/preadv/pwritev to their *64 variants, while on arm64 off_t is
 *      already 64 bits so nothing is redirected. This group blocks loading the
 *      interpreter itself, so it takes the whole Python subsystem down with it — on
 *      arm64 too, which is easy to miss because a device running API 24+ resolves both
 *      name sets from libc and never notices.
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
 *   3) libmpv.so — the player itself, both ABIs:
 *
 *          __write_chk, freeifaddrs, getifaddrs
 *          fseeko64                                 (32-bit ABI only)
 *
 *      Found by auditing the *remaining* prebuilt libraries (app/src/armeabi_v7a/ and
 *      app/src/arm64_v8a/assets/mpv-libs/, plus app/src/main/jniLibs/) once groups 1
 *      and 2 were closed — same root cause, different library. FORTIFY is what pulls
 *      in __write_chk; _FILE_OFFSET_BITS=64 is what pulls in fseeko64, because from
 *      API 24 the NDK's stdio.h __RENAME()s fseeko to fseeko64 in that configuration.
 *      getifaddrs/freeifaddrs are the very functions group 2's if_nameindex() had to
 *      work around. The bundled ijkplayer libraries are the only prebuilt natives that
 *      came back clean.
 *
 *      This group fails silently: MPVLib caches the failure and MPV simply never
 *      becomes available, so playback dies with no crash and nothing in the app log.
 *
 * Because the group-1 failure happened inside BaseLoader's static initialiser, it used
 * to take the whole config loader down with it.
 *
 * WHAT THIS DOES
 * --------------
 * Implements all of them on top of primitives Android 6 does have (fcntl, lseek64,
 * fseeko, readv, writev, prlimit64, SIOCGIFCONF) and exports them under the LIBC_N
 * version node via libc_shim.map, so the versioned references resolve. The list is
 * complete for the APK as built; if a future Chaquopy upgrade or MPV refresh adds
 * native libraries, re-run apk-check/audit_sym_versions.py rather than guessing.
 *
 * Load order matters: this library must be loaded *before* the libraries that need it.
 * That is done in com.github.catvod.utils.LibcShim, which is called from Chaquopy's
 * Platform.loadNativeLibs() and from MPVLib.ensureLoaded().
 *
 * Load order alone is NOT enough, though. libpython3.10.so does not list us in its
 * DT_NEEDED, so it can only see our symbols if we are in the *global* scope when it is
 * relocated — and a plain dlopen() defaults to RTLD_LOCAL. The library therefore marks
 * itself DF_1_GLOBAL (see -Wl,-z,global in CMakeLists.txt), which bionic honours on
 * load regardless of the flags the caller passed.
 *
 * DELIBERATE LIMITATIONS
 * ----------------------
 * - lockf()/lockf64() only support offsets/lengths that fit in 32 bits on a 32-bit ABI,
 *   because they are built on the 32-bit fcntl path. Bigger regions get EOVERFLOW rather
 *   than a wrong answer.
 * - preadv/pwritev and their 64-bit variants are emulated with lseek64 + readv/writev,
 *   so they are neither atomic nor thread-safe with respect to other I/O on the same fd.
 *   CPython only reaches them via os.preadv()/os.pwritev(), which nothing in this
 *   project uses. A load-time failure would be far worse than this trade-off.
 * - if_nameindex() is built on the SIOCGIFCONF ioctl rather than getifaddrs(), because
 *   getifaddrs() is itself API 24. SIOCGIFCONF reports one entry per *address*, so
 *   interfaces with several addresses (or aliases) are de-duplicated by name.
 * - prlimit() narrows the 64-bit result back to the 32-bit struct rlimit. A limit
 *   above 4 GiB would be reported as truncated; no limit Android 6 sets by default
 *   comes close, and CPython only exposes this through resource.prlimit().
 * - getifaddrs() is likewise built on SIOCGIFCONF, so it reports AF_INET entries only:
 *   no IPv6 and no AF_PACKET entries. Consumers that filter on ifa_addr->sa_family
 *   simply see no such entry, exactly as they would on a device with no IPv6 address.
 * - fseeko64() delegates to fseeko(), which exists on Android 6 but on a 32-bit ABI
 *   takes a 32-bit off_t. Offsets that do not fit are refused with EOVERFLOW rather
 *   than seeking somewhere else — the same policy lockf64() uses. mpv reaches its
 *   large-offset paths through lseek64(), which Android 6 does export.
 * - __write_chk() refuses an over-long write instead of calling __fortify_fail() the
 *   way bionic's does. The only caller is a native library whose writes are already
 *   correct, so a false positive would become the very crash this file prevents.
 * - If a future CPython or MPV starts using these on a hot path, revisit this file.
 *
 * Kept as C (not C++) so it stays independent of the C++ runtime.
 */

#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <ifaddrs.h>         /* struct ifaddrs */
#include <net/if.h>          /* struct if_nameindex, struct ifreq/ifconf, IFNAMSIZ */
#include <linux/sockios.h>   /* SIOCGIFCONF, SIOCGIFINDEX, SIOCGIFFLAGS, SIOCGIFNETMASK */
#include <netinet/in.h>      /* struct sockaddr_in */
#include <stdint.h>
#include <stdio.h>           /* FILE, fseeko */
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

/* The struct definitions in net/if.h and ifaddrs.h are unconditional, but every one of
 * these declarations sits behind __INTRODUCED_IN(24). Declaring them here keeps the file
 * correct whichever API level it happens to be compiled for. */
struct if_nameindex *if_nameindex(void);
void if_freenameindex(struct if_nameindex *ptr);
int getifaddrs(struct ifaddrs **ifap);
void freeifaddrs(struct ifaddrs *ifa);
int fseeko64(FILE *fp, off64_t offset, int whence);
ssize_t __write_chk(int fd, const void *buf, size_t count, size_t buf_size);

/* ------------------------------------------------------------------ */
/* lockf / lockf64 — POSIX record locking                              */
/* ------------------------------------------------------------------ */

/*
 * Both names are API 24 additions, and which one a library references depends only on
 * its ABI: 32-bit CPython is built with _FILE_OFFSET_BITS=64, so its header redirects
 * lockf to lockf64 and that is the symbol it carries; arm64 already has a 64-bit off_t,
 * so nothing is redirected and it references plain lockf. Android 6's libc exports
 * neither, under any version node.
 */

static int shim_lockf_impl(int fd, int cmd, off64_t len) {
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

int lockf64(int fd, int cmd, off64_t len) {
    return shim_lockf_impl(fd, cmd, len);
}

/* On a 64-bit ABI off_t is already 64-bit, so this widening is a no-op. */
int lockf(int fd, int cmd, off_t len) {
    return shim_lockf_impl(fd, cmd, (off64_t) len);
}

/* ------------------------------------------------------------------ */
/* preadv / pwritev (and their 64-bit variants) — vectored I/O         */
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

/*
 * The un-suffixed names are what a 64-bit ABI carries, for the same reason as lockf()
 * above: with off_t already 64-bit nothing is redirected. Android 6's libc has no
 * preadv()/pwritev() either — they too are API 24 additions.
 */

ssize_t preadv(int fd, const struct iovec *iov, int iovcnt, off_t offset) {
    return shim_iovec_at(fd, iov, iovcnt, (off64_t) offset, 0);
}

ssize_t pwritev(int fd, const struct iovec *iov, int iovcnt, off_t offset) {
    return shim_iovec_at(fd, iov, iovcnt, (off64_t) offset, 1);
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

/* ------------------------------------------------------------------ */
/* getifaddrs / freeifaddrs — interface address list (API 24)           */
/* ------------------------------------------------------------------ */

/*
 * Needed by libmpv.so on BOTH ABIs (group 3 above).
 *
 * SIOCGIFCONF is the only interface-enumeration primitive Android 6 has. It is
 * AF_INET-only and reports one entry per *address*, so interfaces carrying several
 * addresses are de-duplicated by name — exactly the basis if_nameindex() above uses.
 * IPv6 and AF_PACKET entries are therefore not produced; see the limitations listed at
 * the top of this file.
 */

/* Every address handed out below is an IPv4 sockaddr, so one size covers them all. */
static struct sockaddr *shim_sockaddr_in_dup(const struct sockaddr *src) {
    struct sockaddr *dst = (struct sockaddr *) malloc(sizeof(struct sockaddr_in));

    if (dst != NULL) memcpy(dst, src, sizeof(struct sockaddr_in));
    return dst;
}

int getifaddrs(struct ifaddrs **ifap) {
    int fd;
    struct ifconf ifc;
    char buf[4096];
    struct ifaddrs *head = NULL;
    struct ifaddrs *tail = NULL;
    int count, i;

    if (ifap == NULL) {
        errno = EINVAL;
        return -1;
    }
    *ifap = NULL;

    fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return -1;

    memset(&ifc, 0, sizeof(ifc));
    ifc.ifc_len = (int) sizeof(buf);
    ifc.ifc_buf = buf;

    if (ioctl(fd, SIOCGIFCONF, &ifc) < 0) {
        close(fd);
        return -1;
    }

    count = (int) (ifc.ifc_len / (int) sizeof(struct ifreq));

    for (i = 0; i < count; i++) {
        struct ifaddrs *ifa;
        struct ifaddrs *p;
        struct ifreq req;
        const char *name = ifc.ifc_req[i].ifr_name;
        int duplicate = 0;

        for (p = head; p != NULL; p = p->ifa_next) {
            if (strcmp(p->ifa_name, name) == 0) {
                duplicate = 1;
                break;
            }
        }
        if (duplicate) continue;

        ifa = (struct ifaddrs *) calloc(1, sizeof(*ifa));
        if (ifa == NULL) goto fail;

        /* Link the node in before filling it in, so the error path below can free
         * everything with a single freeifaddrs() and no second bookkeeping list. */
        if (tail == NULL) head = ifa;
        else tail->ifa_next = ifa;
        tail = ifa;

        ifa->ifa_name = strdup(name);
        if (ifa->ifa_name == NULL) goto fail;

        ifa->ifa_addr = shim_sockaddr_in_dup(&ifc.ifc_req[i].ifr_addr);
        if (ifa->ifa_addr == NULL) goto fail;

        memset(&req, 0, sizeof(req));
        strncpy(req.ifr_name, ifa->ifa_name, IFNAMSIZ - 1);

        if (ioctl(fd, SIOCGIFFLAGS, &req) == 0) ifa->ifa_flags = (unsigned) req.ifr_flags;

        memset(&req, 0, sizeof(req));
        strncpy(req.ifr_name, ifa->ifa_name, IFNAMSIZ - 1);

        if (ioctl(fd, SIOCGIFNETMASK, &req) == 0) {
            ifa->ifa_netmask = shim_sockaddr_in_dup(&req.ifr_netmask);
            if (ifa->ifa_netmask == NULL) goto fail;
        }

        /* ifa_ifu is a union, and which member is meaningful depends on the flags. */
        memset(&req, 0, sizeof(req));
        strncpy(req.ifr_name, ifa->ifa_name, IFNAMSIZ - 1);

        if ((ifa->ifa_flags & IFF_BROADCAST) != 0) {
            if (ioctl(fd, SIOCGIFBRDADDR, &req) == 0) {
                ifa->ifa_ifu.ifu_broadaddr = shim_sockaddr_in_dup(&req.ifr_broadaddr);
                if (ifa->ifa_ifu.ifu_broadaddr == NULL) goto fail;
            }
        } else if ((ifa->ifa_flags & IFF_POINTOPOINT) != 0) {
            if (ioctl(fd, SIOCGIFDSTADDR, &req) == 0) {
                ifa->ifa_ifu.ifu_dstaddr = shim_sockaddr_in_dup(&req.ifr_dstaddr);
                if (ifa->ifa_ifu.ifu_dstaddr == NULL) goto fail;
            }
        }
    }

    close(fd);
    *ifap = head;
    return 0;

fail:
    {
        int saved = errno;

        close(fd);
        freeifaddrs(head);
        errno = saved;
    }
    return -1;
}

void freeifaddrs(struct ifaddrs *ifa) {
    while (ifa != NULL) {
        struct ifaddrs *next = ifa->ifa_next;

        free(ifa->ifa_name);
        free(ifa->ifa_addr);
        free(ifa->ifa_netmask);
        /* ifa_broadaddr and ifa_dstaddr are the same union member, so this one free
         * covers whichever of the two getifaddrs() filled in. */
        free(ifa->ifa_ifu.ifu_broadaddr);
        free(ifa);
        ifa = next;
    }
}

/* ------------------------------------------------------------------ */
/* fseeko64 — 64-bit stream seek (API 24)                               */
/* ------------------------------------------------------------------ */

/*
 * Needed by libmpv.so on the 32-bit ABI only (group 3 above): it is built with
 * _FILE_OFFSET_BITS=64, and from API 24 onwards the NDK's stdio.h __RENAME()s fseeko to
 * fseeko64 in that configuration, so the reference turns up wherever a FILE* is seeked.
 * On arm64 off_t is already 64-bit and nothing is redirected, hence the ABI split.
 *
 * fseeko() itself has existed since API 1, but on a 32-bit ABI its off_t is 32 bits, so
 * it cannot express a position beyond 2 GiB. Those are refused rather than turned into a
 * seek somewhere else — the same policy lockf64() uses above.
 */

/*
 * Bind to the pre-N symbol explicitly. If this file is ever compiled with
 * _FILE_OFFSET_BITS=64 the declaration in stdio.h would be renamed to fseeko64, and a
 * plain fseeko() call here would land straight back in this function.
 */
extern int shim_fseeko(FILE *fp, off_t offset, int whence) __asm__("fseeko");

int fseeko64(FILE *fp, off64_t offset, int whence) {
#if !defined(__LP64__)
    if (offset > (off64_t) INT32_MAX || offset < (off64_t) INT32_MIN) {
        errno = EOVERFLOW;
        return -1;
    }
#endif
    return shim_fseeko(fp, (off_t) offset, whence);
}

/* ------------------------------------------------------------------ */
/* __write_chk — FORTIFY wrapper for write() (API 24)                   */
/* ------------------------------------------------------------------ */

/*
 * Needed by libmpv.so on BOTH ABIs (group 3 above): it is built with FORTIFY enabled,
 * so a write() whose buffer size the compiler can see becomes a call to this symbol.
 * The read side was not deferred — __read_chk has been in bionic since API 1 — which is
 * why only write is missing.
 *
 * bionic's own version calls __fortify_fail(), which aborts the process. We refuse the
 * write instead: the only caller is a native library whose writes are already correct,
 * so a false positive would become the very crash this file exists to prevent.
 */

/*
 * Reach the real write() under a different identifier. bionic's FORTIFY wrapper routes
 * any call whose buffer size it cannot prove straight into __write_chk, and a plain
 * pointer parameter is exactly that case — so calling write() from in here would recurse
 * until the stack ran out. bits/fortify/unistd.h uses the same trick for its own
 * __*_real declarations.
 */
extern ssize_t shim_write_real(int fd, const void *buf, size_t count) __asm__("write");

ssize_t __write_chk(int fd, const void *buf, size_t count, size_t buf_size) {
    if (count > 0 && (buf == NULL || count > buf_size)) {
        errno = EINVAL;
        return -1;
    }
    return shim_write_real(fd, buf, count);
}
