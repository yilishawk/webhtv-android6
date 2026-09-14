cmake_minimum_required(VERSION 3.22.1)

# C is enabled for libc_shim.c; everything else here is C++.
project(exo_dovi_renderer LANGUAGES C CXX)

# ---------------------------------------------------------------------------
# webhtv_shim — bionic compatibility shim for Android 6 (API 23).
#
# Supplies the 25 bionic symbols that were added in Android 7.0 but are referenced by
# prebuilt native libraries in this APK. Android 6's libc exports none of them, and
# System.load()/loadLibrary() resolve with RTLD_NOW, so any one of them missing makes
# the whole dlopen() fail. Three consumers:
#
#   Chaquopy's CPython   lockf/lockf64, preadv/preadv64, pwritev/pwritev64,
#                        if_nameindex, if_freenameindex, prlimit
#   the bundled MPV      __write_chk, getifaddrs, freeifaddrs, fseeko64
#   the MPV codec stack  __aeabi_memcpy/memmove/memset/memclr and their 4- and
#                        8-suffixed forms (32-bit only; see group 4 in libc_shim.c)
#
# Loaded by com.github.catvod.utils.LibcShim, which both Platform (Chaquopy) and
# MPVLib call before loading their own libraries.
# See libc_shim.c for the full rationale, including which ABI needs which name.
#
# Built for every ABI on purpose: each ABI needs only a subset, so the extra
# definitions are inert, and it keeps System.loadLibrary("webhtv_shim") valid
# regardless of ABI. It is only *loaded* on API < 24.
# ---------------------------------------------------------------------------
add_library(webhtv_shim SHARED libc_shim.c)

target_compile_options(webhtv_shim PRIVATE
        -Wall -Wextra
        # Group 4 (__aeabi_mem*) implements its loops in C and must NOT have them
        # recognised as calls to memcpy/memmove/memset: on ARM clang lowers such a call to
        # the matching __aeabi_* name, which is the name being defined — the forwarder
        # would call itself. See the comment beside those definitions in libc_shim.c.
        -fno-builtin-memcpy
        -fno-builtin-memmove
        -fno-builtin-memset)
target_link_options(webhtv_shim PRIVATE
        -Wl,--no-undefined
        # Stamp DT_FLAGS_1 | DF_1_GLOBAL into the .so.
        #
        # libpython3.10.so does NOT list us in its DT_NEEDED — it just has an
        # undefined lockf64@LIBC_N that it expects libc to provide. So it can only
        # find ours if this library is in the *global* scope at the time libpython
        # is relocated. bionic's dlopen() resolves symbols against the global group
        # plus the library's own dependency closure, and RTLD_LOCAL (the default)
        # puts a freshly dlopen'ed library in a *local* group only.
        #
        # Rather than depend on whatever flags System.loadLibrary() happens to pass
        # (an implementation detail that has changed between releases), mark the
        # library itself as global. bionic's load_library() reads this tag straight
        # off the ELF:
        #
        #     if (d->d_tag == DT_FLAGS_1) si->set_dt_flags_1(d->d_un.d_val);
        #
        # and find_libraries() then puts any not-yet-linked library with that bit set
        # into the global group of every namespace:
        #
        #     if (is_ld_preload || (si->get_dt_flags_1() & DF_1_GLOBAL) != 0) { ... }
        #
        # So the promotion happens on load, deterministically, on every Android
        # version. (Verify with: llvm-readelf -d libwebhtv_shim.so | grep FLAGS_1
        #  -> should print "NOW GLOBAL".)
        -Wl,-z,global
        "-Wl,--version-script=${CMAKE_CURRENT_LIST_DIR}/libc_shim.map")
# libc is linked implicitly by the NDK toolchain; --no-undefined is satisfied by it.

# ---------------------------------------------------------------------------
# vulkan_stub.c is deliberately NOT a target in this file.
#
# It builds libvulkan.so, which libmpv.so names in its DT_NEEDED — Vulkan arrived in
# API 24, so Android 6 has no such library and the loader fails before resolving any
# symbol. Supplying it means supplying a *file*, which is why the libc shim above cannot
# help with this one.
#
# Every target in this file is packaged by AGP into the APK's lib/<abi>/. That is exactly
# where this library must NOT go: a library under lib/ joins the app's native-library
# search path, so on a device that does have Vulkan it would shadow the system's real
# libvulkan.so for the whole app. It has to ship in the asset bundle instead, so that
# MPVLib can preload it by absolute path and only below API 24 — see
# MPVLib.preloadVulkanStub().
#
# So the built libvulkan.so is committed alongside the other prebuilt libraries in
# app/src/<abi>/assets/mpv-libs/<abi>/, exactly as the ten MPV libraries already are, and
# vulkan_stub.c is kept next to this file as its source of truth. To rebuild after editing
# it (NDK 29.0.14206865, both ABIs):
#
#   NDK=$SDK/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin
#   "$NDK/armv7a-linux-androideabi26-clang" -shared -fPIC -O2 -Wall -Wextra \
#       -Wl,--no-undefined -o .../mpv-libs/armeabi-v7a/libvulkan.so vulkan_stub.c -llog
#   "$NDK/aarch64-linux-android26-clang" -shared -fPIC -O2 -Wall -Wextra \
#       -Wl,--no-undefined -o .../mpv-libs/arm64-v8a/libvulkan.so vulkan_stub.c -llog
#
# Then confirm it still exports everything libmpv.so asks for — the 63 vk* entry points,
# identical on both ABIs — by re-running apk-check/audit_sym_versions.py.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# exo_dovi_renderer
# ---------------------------------------------------------------------------
add_library(exo_dovi_renderer SHARED exo_dovi_renderer.cpp)

set(EXO_DV5_NATIVE_ROOT
        "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/exo-dv5-native")
set(EXO_DV5_NATIVE_LIB_DIR
        "${EXO_DV5_NATIVE_ROOT}/prebuilt/${ANDROID_ABI}")

add_library(exo_dv5_placebo STATIC IMPORTED)
set_target_properties(exo_dv5_placebo PROPERTIES
        IMPORTED_LOCATION "${EXO_DV5_NATIVE_LIB_DIR}/libplacebo.a")

add_library(exo_dv5_shaderc STATIC IMPORTED)
set_target_properties(exo_dv5_shaderc PROPERTIES
        IMPORTED_LOCATION "${EXO_DV5_NATIVE_LIB_DIR}/libshaderc.a")

add_library(exo_dv5_dovi STATIC IMPORTED)
set_target_properties(exo_dv5_dovi PROPERTIES
        IMPORTED_LOCATION "${EXO_DV5_NATIVE_LIB_DIR}/libdovi.a")

target_compile_features(exo_dovi_renderer PRIVATE cxx_std_20)
target_compile_options(exo_dovi_renderer PRIVATE
        -fvisibility=hidden
        -fvisibility-inlines-hidden)
target_compile_definitions(exo_dovi_renderer PRIVATE
        PL_STATIC
        PL_HAVE_PTHREAD
        PTHREAD_HAS_SETCLOCK)
target_include_directories(exo_dovi_renderer PRIVATE
        "${EXO_DV5_NATIVE_ROOT}/include")
target_link_options(exo_dovi_renderer PRIVATE -Wl,--no-undefined)

find_library(android-lib android REQUIRED)
find_library(log-lib log REQUIRED)
find_library(media-ndk-lib mediandk REQUIRED)
find_library(vulkan-lib vulkan REQUIRED)
find_library(dl-lib dl REQUIRED)
find_library(m-lib m REQUIRED)

target_link_libraries(exo_dovi_renderer PRIVATE
        exo_dv5_placebo
        exo_dv5_shaderc
        exo_dv5_dovi
        ${android-lib}
        ${log-lib}
        ${media-ndk-lib}
        ${vulkan-lib}
        ${dl-lib}
        ${m-lib})
