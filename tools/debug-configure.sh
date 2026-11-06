#!/bin/bash
set -x
cd /opt/libmpv-lgpl/ffmpeg
rm -rf ffbuild
NDK=/opt/android-ndk-r29
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
./configure \
  --cc=$TC/bin/aarch64-linux-android24-clang \
  --ar=$TC/bin/llvm-ar \
  --strip=$TC/bin/llvm-strip \
  --sysroot=$TC/sysroot \
  --prefix=/opt/libmpv-lgpl/prefix-arm64 \
  --target-os=android \
  --arch=aarch64 \
  --enable-cross-compile \
  --enable-shared --disable-static \
  --disable-programs --disable-doc \
  --disable-avdevice --disable-avfilter --disable-network \
  --disable-gpl --disable-nonfree --disable-version3 \
  --enable-pthreads --enable-jni --enable-mediacodec \
  --extra-cflags='-Os -fPIC' \
  2>&1
