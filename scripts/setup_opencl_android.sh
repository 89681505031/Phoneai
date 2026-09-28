#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
THIRD="$ROOT/third_party"
HEADERS="$THIRD/OpenCL-Headers"
LOADER="$THIRD/OpenCL-ICD-Loader"
TAG="v2026.05.29"
NDK_VERSION="${PHONEAI_NDK_VERSION:-29.0.13113456}"
NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}/ndk/$NDK_VERSION}}"

if [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
  echo "Android NDK not found at: $NDK" >&2
  exit 1
fi

mkdir -p "$THIRD"
if [ ! -d "$HEADERS/.git" ]; then git clone --depth 1 --branch "$TAG" https://github.com/KhronosGroup/OpenCL-Headers.git "$HEADERS"; fi
if [ ! -d "$LOADER/.git" ]; then git clone --depth 1 --branch "$TAG" https://github.com/KhronosGroup/OpenCL-ICD-Loader.git "$LOADER"; fi

SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
if [ ! -d "$SYSROOT" ]; then
  PREBUILT="$(find "$NDK/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d | head -1)"
  SYSROOT="$PREBUILT/sysroot"
fi
cp -R "$HEADERS/CL" "$SYSROOT/usr/include/"

BUILD="$LOADER/build-phoneai-android"
rm -rf "$BUILD"
cmake -S "$LOADER" -B "$BUILD" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DOPENCL_ICD_LOADER_HEADERS_DIR="$SYSROOT/usr/include" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=28 \
  -DANDROID_STL=c++_shared \
  -DBUILD_TESTING=OFF
cmake --build "$BUILD"

OPENCL_SO="$(find "$BUILD" -name libOpenCL.so -type f | head -1)"
if [ -z "$OPENCL_SO" ]; then echo "libOpenCL.so was not produced" >&2; exit 1; fi
mkdir -p "$SYSROOT/usr/lib/aarch64-linux-android"
cp "$OPENCL_SO" "$SYSROOT/usr/lib/aarch64-linux-android/libOpenCL.so"
JNI="$ROOT/third_party/llama.cpp/examples/llama.android/lib/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI"
cp "$OPENCL_SO" "$JNI/libOpenCL.so"
echo "OpenCL Android loader prepared: $OPENCL_SO"
