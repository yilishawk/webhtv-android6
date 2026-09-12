cmake_minimum_required(VERSION 3.22.1)

# C is enabled for libc_shim.c; everything else here is C++.
project(exo_dovi_renderer LANGUAGES C CXX)

# ---------------------------------------------------------------------------
# webhtv_shim — bionic compatibility shim for 32-bit Android 6 (API 23).
#
# Supplies lockf64 / preadv64 / pwritev64, which Chaquopy's libpython3.10.so needs
# (it is built for android-24) but Android 6's bionic does not export. Loaded from
# chaquo/src/main/java/com/fongmi/chaquo/Platform.java before Chaquopy's own libs.
# See libc_shim.c for the full rationale.
#
# Built for every ABI on purpose: on arm64 nothing references those symbols, so the
# extra definitions are inert, and it keeps System.loadLibrary("webhtv_shim") valid
# regardless of ABI. It is only *loaded* on API < 24.
# ---------------------------------------------------------------------------
add_library(webhtv_shim SHARED libc_shim.c)

target_compile_options(webhtv_shim PRIVATE -Wall -Wextra)
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
