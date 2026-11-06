#!/usr/bin/env bash
# 在 WSL 中执行：构建 LGPL 版 libmpv + ffmpeg（Android 双 ABI）
set -euo pipefail

NDK="/opt/android-ndk-r29"  # WSL 本地 Linux NDK（Windows NDK 的工具链是 .exe，WSL 用不了）
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$REPO_ROOT/player/src/main/jniLibs"
WORK="/opt/libmpv-lgpl"  # /tmp 会被 WSL 清理，用持久目录
JOBS=$(nproc)
API=24

echo "== 安装依赖 =="
apt-get update -qq && apt-get install -y -qq meson ninja-build python3-pip 2>&1 | tail -1

echo "== clone（tarball 方式，比 git clone 更稳定）=="
mkdir -p "$WORK" && cd "$WORK"
if [ ! -f ffmpeg/configure ]; then
  curl -sL https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n8.1.tar.gz | tar xz
  mv FFmpeg-n8.1 ffmpeg
fi
if [ ! -f mpv/meson.build ]; then
  MPV_TAG=$(curl -sL "https://api.github.com/repos/mpv-player/mpv/tags?per_page=1" | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['name'])" 2>/dev/null || echo "v0.40.0")
  curl -sL "https://github.com/mpv-player/mpv/archive/refs/tags/${MPV_TAG}.tar.gz" | tar xz
  mv mpv-* mpv
fi
MPV_VER=$(ls "$WORK/mpv/VERSION" 2>/dev/null && cat "$WORK/mpv/VERSION" || echo "v0.40")
FF_VER="n8.1"

for ABI in arm64-v8a armeabi-v7a; do
  PREFIX="$WORK/prefix-$ABI"
  TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
  SYSROOT="$TOOLCHAIN/sysroot"

  case $ABI in
    arm64-v8a) TRIPLE=aarch64-linux-android ;;
    armeabi-v7a) TRIPLE=armv7a-linux-androideabi ;;
  esac
  CC="$TOOLCHAIN/bin/${TRIPLE}${API}-clang"
  CXX="$TOOLCHAIN/bin/${TRIPLE}${API}-clang++"
  AR="$TOOLCHAIN/bin/llvm-ar"
  STRIP="$TOOLCHAIN/bin/llvm-strip"

  # ---- ffmpeg ----
  echo "== [$ABI] ffmpeg（LGPL，spdif muxer 开）==="
  cd "$WORK/ffmpeg"
  make distclean 2>/dev/null || true
  ./configure \
    --cc="$CC" --cxx="$CXX" --ar="$AR" --strip="$STRIP" \
    --sysroot="$SYSROOT" --prefix="$PREFIX" \
    --target-os=android \
    --enable-cross-compile \
    --enable-shared --disable-static \
    --disable-programs --disable-doc --disable-avdevice --disable-avfilter \
    --disable-network \
    --disable-gpl --disable-nonfree --disable-version3 \
    --enable-pthreads --enable-jni --enable-mediacodec \
    --extra-cflags="-Os -fPIC" \
    2>&1 | tail -3
  make -j$JOBS 2>&1 | tail -1
  make install 2>&1 | tail -1

  # ---- meson cross file ----
  cat > "$WORK/cross-$ABI.meson" << EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkg-config = 'pkg-config'

[properties]
sys_root = '$SYSROOT'
c_args = ['-DANDROID', '-D__ANDROID_API__=$API', '--sysroot=$SYSROOT', '-fPIC', '-I$PREFIX/include']
c_link_args = ['--sysroot=$SYSROOT', '-L$PREFIX/lib', '-Wl,-rpath=$PREFIX/lib']
pkg_config_libdir = '$PREFIX/lib/pkgconfig'

[host_machine]
system = 'android'
cpu_family = '$( [ $ABI = arm64-v8a ] && echo aarch64 || echo arm )'
cpu = '$( [ $ABI = arm64-v8a ] && echo aarch64 || echo armv7 )'
endian = 'little'
EOF

  # ---- libmpv ----
  echo "== [$ABI] libmpv（LGPL 模式）==="
  cd "$WORK/mpv"
  rm -rf build-$ABI
  export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
  export PKG_CONFIG_SYSROOT_DIR=""
  export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"

  meson setup build-$ABI \
    --cross-file "$WORK/cross-$ABI.meson" \
    --buildtype release \
    --prefix "$PREFIX" \
    -Dlibmpv=true -Dcplayer=false -Dgpl=false \
    -Dmanpage-build=disabled -Dlua=disabled -Djavascript=disabled \
    -Ddvdnav=disabled -Dcdda=disabled -Dlibarchive=disabled \
    -Duchardet=disabled -Drubberband=disabled -Dlcms2=disabled \
    -Dvulkan=disabled -Djpeg=disabled -Dvapoursynth=disabled \
    -Dgl=enabled -Degl-android=enabled \
    -Dandroid-media-ndk=enabled \
    -Dopensles=enabled -Daudio-output=enabled \
    2>&1 | tail -5

  ninja -C build-$ABI 2>&1 | tail -3

  # ---- 拷贝 ----
  mkdir -p "$OUT_DIR/$ABI"
  cp "build-$ABI/libmpv.so" "$OUT_DIR/$ABI/"
  for lib in libavcodec libavformat libavutil libswscale libswresample; do
    [ -f "$PREFIX/lib/$lib.so" ] && cp "$PREFIX/lib/$lib.so" "$OUT_DIR/$ABI/"
  done
  echo "== [$ABI] done: $(ls $OUT_DIR/$ABI/*.so | wc -l) .so files"
done

echo "$MPV_VER (mpv) + $FF_VER (ffmpeg n8.1 LGPL)" > "$OUT_DIR/libmpv-lgpl-version.txt"
echo "== ALL DONE =="
