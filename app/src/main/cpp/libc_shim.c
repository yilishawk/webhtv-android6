/*
 * webhtv_shim — bionic compatibility shim for 32-bit Android 6 (API 23).
 *
 * WHY THIS EXISTS
 * ---------------
 * The bundled CPython (Chaquopy 17, libpython3.10.so) is built for android-24 with
 * _FILE_OFFSET_BITS=64. On a 32-bit ABI that makes it reference three bionic symbols
 * that only exist from Android 7.0 (API 24) onwards:
 *
 *     lockf64      preadv64      pwritev64
 *
 * Android 6's libc.so does not export them, and System.loadLibrary() resolves with
 * RTLD_NOW, so every undefined symbol must be satisfiable at load time. The result is
 *
 *     dlopen failed: cannot locate symbol "lockf64" referenced by ".../base.apk"
 *
 * and Python spiders die. Because the failure happens inside BaseLoader's static
 * initialiser, it used to take the whole config loader down with it.
 *
 * This is NOT an arm64 problem: there off_t is already 64-bit, so CPython calls
 * lockf()/preadv() directly and needs none of the *64 variants. That is exactly why
 * the arm64 phone worked and the 32-bit TV did not.
 *
 * WHAT THIS DOES
 * --------------
 * Implements the three missing symbols on top of primitives Android 6 does have
 * (fcntl, lseek64, readv, writev) and exports them under the LIBC_N version node via
 * libc_shim.map, so the versioned references inside libpython3.10.so resolve.
 *
 * Load order matters: this library must be loaded *before* Chaquopy's. That is done in
 * chaquo/src/main/java/com/fongmi/chaquo/Platform.java, immediately before its
 * loadNativeLibs() loop.
 *
 * DELIBERATE LIMITATIONS
 * ----------------------
 * - lockf64 only supports offsets/lengths that fit in 32 bits, because it is built on
 *   the 32-bit fcntl path. Bigger regions get EOVERFLOW rather than a wrong answer.
 * - preadv64/pwritev64 are emulated with lseek64 + readv/writev, so they are neither
 *   atomic nor thread-safe with respect to other I/O on the same fd. CPython only
 *   reaches them via os.preadv()/os.pwritev(), which nothing in this project uses.
 *   A load-time failure would be far worse than this trade-off.
 * - If a future CPython starts using these on a hot path, revisit this file.
 *
 * Kept as C (not C++) so it stays independent of the C++ runtime.
 */

#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <unistd.h>

#if !defined(__BIONIC__)
#error "This shim is only meaningful against Android's bionic libc."
#endif

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
