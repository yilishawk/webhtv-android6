#!/usr/bin/env bash
# Rebuild the libvulkan.so stub that ships in the MPV asset bundles.
#
# Why this file exists at all: the committed libmpv.so has `libvulkan.so` in its
# DT_NEEDED list, but Vulkan only appeared in Android at API 24, so Android 6
# devices have no such library and `System.load(libmpv.so)` fails outright with
# `library "libvulkan.so" not found`.  A symbol shim cannot fix a missing *file*.
#
# The stub deliberately behaves like a Vulkan loader with no driver installed
# (vkCreateInstance -> VK_ERROR_INCOMPATIBLE_DRIVER), which is the normal path by
# which mpv's `gpu-api=auto` falls back to GL.  On API 24+ devices it must never be
# loaded at all: MPVLib.preloadVulkanStub() is gated on SDK_INT < 24 so the real
# platform loader keeps winning.
#
# Output goes to the asset bundle, NOT to lib/<abi>/ -- a library under lib/ joins
# the app's native-library search path and would shadow the system's real
# libvulkan.so on devices that do have Vulkan.
#
# Usage: scripts/build_libvulkan_stub.sh [path/to/ndk]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/cpp/vulkan_stub.c"
NDK="${1:-${ANDROID_NDK_HOME:-${NDK_HOME:-}}}"

[ -f "$SRC" ] || { printf 'missing source: %s\n' "$SRC" >&2; exit 1; }
[ -n "$NDK" ] || { printf 'usage: %s <ndk-dir>   (or set ANDROID_NDK_HOME)\n' "$0" >&2; exit 1; }

HOST_BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
[ -d "$HOST_BIN" ] || HOST_BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -d "$HOST_BIN" ] || HOST_BIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
[ -d "$HOST_BIN" ] || { printf 'cannot locate NDK prebuilt bin under %s\n' "$NDK" >&2; exit 1; }

# API 26 to match app/build.gradle's -DANDROID_PLATFORM (the CMake target level for
# everything else in this project); the stub only uses liblog, so the level is not
# load-bearing, but keeping it uniform avoids surprises.
API=26

# -Wl,-soname is required: scripts/verify_mpv_native_assets.sh asserts that every
# bundled .so's SONAME equals its file name.  Do NOT add -Wl,-z,global here: this
# library is reached through libmpv.so's DT_NEEDED closure, so bionic resolves its
# symbols there and it has no need to export itself to the global group.
# The NDK toolchain is a native Windows binary, so it cannot see MSYS-style
# /c/... paths; hand it C:/... instead.
winpath() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s\n' "$1" | sed -e 's|^/\([a-zA-Z]\)/|\1:/|'
  fi
}

build_one() {
  local abi="$1"
  local triple="$2"
  local flavor="$3"
  local out="$ROOT/app/src/$flavor/assets/mpv-libs/$abi/libvulkan.so"
  printf 'building %s -> %s\n' "$abi" "$out"
  "$HOST_BIN/${triple}${API}-clang" \
    -shared -fPIC -O2 -Wall -Wextra -D_FORTIFY_SOURCE=2 \
    -Wl,-soname,libvulkan.so -Wl,--no-undefined \
    -o "$(winpath "$out")" "$(winpath "$SRC")" -llog -ldl
  "$HOST_BIN/llvm-readelf" -d "$(winpath "$out")" | grep -E 'SONAME|NEEDED'
}

build_one armeabi-v7a armv7a-linux-androideabi armeabi_v7a
build_one arm64-v8a   aarch64-linux-android    arm64_v8a

printf 'done. verify with: bash scripts/verify_mpv_native_assets.sh --require-elf\n'
