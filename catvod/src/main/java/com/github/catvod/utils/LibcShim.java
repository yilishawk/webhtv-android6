package com.github.catvod.utils;

import android.os.Build;

/**
 * Loads libwebhtv_shim.so — the bionic compatibility shim described in
 * app/src/main/cpp/libc_shim.c — before any native library that needs it.
 *
 * <p>This fork exists to run on Android 6 (API 23), but several prebuilt native libraries
 * in the APK were compiled against android-24 (or later) headers. They are therefore
 * allowed to reference bionic symbols that only appeared in Android 7.0, and those
 * references carry the version node {@code LIBC_N}. Android 6's libc.so has no LIBC_N
 * node at all, and bionic resolves with {@code RTLD_NOW}, so a single unresolvable symbol
 * makes the whole {@code dlopen()} fail and the entire subsystem disappears:
 *
 * <ul>
 *   <li>Chaquopy's CPython ({@code libpython3.10.so}, {@code _socket}, {@code resource},
 *       …) → no Python spiders, and the config loader used to go down with it.</li>
 *   <li>the bundled MPV ({@code libmpv.so}) → no playback. This one is silent: MPVLib
 *       caches the failure, so there is no crash and nothing in the app log.</li>
 * </ul>
 *
 * <p>The shim supplies those symbols under the same version node, and must be loaded
 * first. Loading it more than once is harmless.
 *
 * <p>Lives in {@code :catvod} because both {@code :chaquo} and {@code :app} depend on that
 * module, and both subsystems need it.
 */
public final class LibcShim {

    /**
     * The symbols the shim exports. Kept here so that the log line and
     * {@code libc_shim.map} cannot drift apart without anyone noticing.
     */
    public static final String SYMBOLS =
            "lockf/lockf64/preadv/preadv64/pwritev/pwritev64"
                    + "/if_nameindex/if_freenameindex/prlimit"
                    + "/getifaddrs/freeifaddrs/fseeko64/__write_chk";

    private static Boolean available;
    private static Throwable error;

    private LibcShim() {
    }

    /**
     * @return whether the shim is usable. Always true from API 24 onwards, where every
     *         one of these symbols is part of libc and there is nothing to load.
     */
    public static synchronized boolean load() {
        if (available != null) return available;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            available = Boolean.TRUE;
            return true;
        }
        try {
            System.loadLibrary("webhtv_shim");
            available = Boolean.TRUE;
        } catch (Throwable e) {
            // Never fatal: the callers degrade to "this subsystem is unavailable", which
            // is exactly what used to happen anyway — but now only for that subsystem.
            available = Boolean.FALSE;
            error = e;
        }
        return available;
    }

    /** The reason {@link #load()} failed, or null. For diagnostics only. */
    public static synchronized Throwable getError() {
        return error;
    }
}
