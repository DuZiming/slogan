#!/usr/bin/env bash
# 在 WSL 中执行：交叉编译 libnfs（LGPL，动态链接）为 Android 双 ABI。
# 用法：bash tools/build-libnfs.sh [NDK路径(wsl风格，默认见下)]
set -euo pipefail

NDK="${1:-/mnt/c/Users/admin/AppData/Local/Android/Sdk/ndk/29.0.14206865}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$REPO_ROOT/player/src/main/jniLibs"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "== clone libnfs =="
git clone --depth 1 https://github.com/sahlberg/libnfs "$WORK/libnfs"
LIBNFS_VERSION="$(git -C "$WORK/libnfs" rev-parse --short HEAD)"

for ABI in arm64-v8a armeabi-v7a; do
  echo "== build $ABI =="
  cmake -S "$WORK/libnfs" -B "$WORK/build-$ABI" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-21 \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILD_EXECUTABLES=OFF \
    -DENABLE_TESTS=OFF >/dev/null
  cmake --build "$WORK/build-$ABI" --target nfs -- -j"$(nproc)" >/dev/null
  mkdir -p "$OUT_DIR/$ABI"
  cp "$WORK/build-$ABI/lib/libnfs.so" "$OUT_DIR/$ABI/libnfs.so"
done

echo "== done: libnfs@$LIBNFS_VERSION → $OUT_DIR/{arm64-v8a,armeabi-v7a}/libnfs.so =="
echo "$LIBNFS_VERSION" > "$REPO_ROOT/player/src/main/jniLibs/libnfs-version.txt"
