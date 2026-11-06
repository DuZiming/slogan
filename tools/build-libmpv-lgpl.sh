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
apt-get update -qq && apt-get install -y -qq meson ninja-build python3-pip build-essential pkg-config nasm yasm 2>&1 | tail -1

echo "== clone（tarball 方式，比 git clone 更稳定）=="
mkdir -p "$WORK" && cd "$WORK"
if [ ! -f ffmpeg/configure ]; then
  curl -sL https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n8.1.tar.gz | tar xz
  mv FFmpeg-n8.1 ffmpeg
fi
if [ ! -f mpv/meson.build ]; then
  MPV_TAG=v0.40.0
  curl -sL "https://github.com/mpv-player/mpv/archive/refs/tags/${MPV_TAG}.tar.gz" | tar xz
  mv mpv-* mpv
fi
if [ ! -f libplacebo/meson.build ]; then
  curl -sL https://github.com/haasn/libplacebo/archive/refs/heads/master.tar.gz | tar xz
  mv libplacebo-master libplacebo
  git clone --depth 1 https://github.com/Dav1dde/glad.git libplacebo/3rdparty/glad 2>/dev/null || true
fi
if [ ! -f freetype/meson.build ]; then
  curl -sL https://download.savannah.gnu.org/releases/freetype/freetype-2.13.3.tar.gz | tar xz
  mv freetype-2.13.3 freetype
fi
if [ ! -f fribidi/meson.build ]; then
  curl -sL --retry 3 https://github.com/fribidi/fribidi/archive/refs/tags/v1.0.16.tar.gz | tar xz
  mv fribidi-* fribidi
fi
if [ ! -f libass/meson.build ]; then
  curl -sL https://github.com/libass/libass/releases/download/0.17.3/libass-0.17.3.tar.gz | tar xz
  mv libass-0.17.3 libass
fi
MPV_VER=$(ls "$WORK/mpv/VERSION" 2>/dev/null && cat "$WORK/mpv/VERSION" || echo "v0.40")
FF_VER="n8.1"

for ABI in arm64-v8a armeabi-v7a; do
  PREFIX="$WORK/prefix-$ABI"
  TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
  SYSROOT="$TOOLCHAIN/sysroot"

  case $ABI in
    arm64-v8a) TRIPLE=aarch64-linux-android; FFMPEG_ARCH=aarch64 ;;
    armeabi-v7a) TRIPLE=armv7a-linux-androideabi; FFMPEG_ARCH=arm ;;
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
    --disable-x86asm \
    --cc="$CC" --cxx="$CXX" --ar="$AR" --strip="$STRIP" \
    --sysroot="$SYSROOT" --prefix="$PREFIX" \
    --target-os=android --arch="$FFMPEG_ARCH" \
    --enable-cross-compile \
    --enable-shared --disable-static \
    --disable-programs --disable-doc --disable-avdevice \
    --disable-network \
    --disable-gpl --disable-nonfree --disable-version3 \
    --enable-pthreads --enable-jni --enable-mediacodec \
    --extra-cflags="-Os -fPIC" \
    2>&1 | tail -3
  make -j$JOBS 2>&1 | tail -1
  make install 2>&1 | tail -1

  # ---- meson cross file（ffmpeg 和后续 meson 项目共用）----
  cat > "$WORK/cross-$ABI.meson" << EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkg-config = 'pkg-config'

[properties]
c_args = ['-DANDROID', '-D__ANDROID_API__=$API', '--sysroot=$SYSROOT', '-fPIC', '-I$PREFIX/include', '-I$PREFIX/include/freetype2', '-DFF_PROFILE_ARIB_PROFILE_A=0', '-DFF_PROFILE_ARIB_PROFILE_C=1', '-DFF_PROFILE_UNKNOWN=-99']
c_link_args = ['--sysroot=$SYSROOT', '-L$PREFIX/lib', '-Wl,-rpath=$PREFIX/lib']
pkg_config_libdir = '$PREFIX/lib/pkgconfig'

[host_machine]
system = 'android'
cpu_family = '$( [ $ABI = arm64-v8a ] && echo aarch64 || echo arm )'
cpu = '$( [ $ABI = arm64-v8a ] && echo aarch64 || echo armv7 )'
endian = 'little'
EOF

  # ---- libplacebo（mpv 的 GPU 渲染依赖，LGPL）----
  echo "== [$ABI] libplacebo =="
  cd "$WORK/libplacebo"
  rm -rf build-$ABI
  export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
  export PYTHONPATH="$WORK/libplacebo/3rdparty/glad:${PYTHONPATH:-}"
  meson setup build-$ABI \
    --cross-file "$WORK/cross-$ABI.meson" \
    --buildtype release \
    --prefix "$PREFIX" \
    -Dvulkan=disabled \
    -Ddemos=false \
    -Dtests=false \
    -Dglslang=disabled \
    2>&1 | tail -3
  ninja -C build-$ABI 2>&1 | tail -1
  ninja -C build-$ABI install 2>&1 | tail -1

  # ---- freetype ----
  echo "== [$ABI] freetype =="
  cd "$WORK/freetype"
  rm -rf build-$ABI
  meson setup build-$ABI \
    --cross-file "$WORK/cross-$ABI.meson" \
    --buildtype release \
    --prefix "$PREFIX" \
    -Dzlib=disabled -Dbzip2=disabled -Dpng=disabled \
    -Dharfbuzz=disabled -Dbrotli=disabled \
    2>&1 | tail -2
  ninja -C build-$ABI 2>&1 | tail -1
  ninja -C build-$ABI install 2>&1 | tail -1

  # ---- fribidi ----
  echo "== [$ABI] fribidi =="
  cd "$WORK/fribidi"
  rm -rf build-$ABI
  meson setup build-$ABI \
    --cross-file "$WORK/cross-$ABI.meson" \
    --buildtype release \
    --prefix "$PREFIX" \
    -Ddocs=false -Dtests=false \
    2>&1 | tail -2
  ninja -C build-$ABI 2>&1 | tail -1
  ninja -C build-$ABI install 2>&1 | tail -1

  # ---- harfbuzz ----
  echo "== [$ABI] harfbuzz =="
  cd "$WORK/harfbuzz"
  rm -rf build-$ABI
  meson setup build-$ABI \
    --cross-file "$WORK/cross-$ABI.meson" \
    --buildtype release \
    --prefix "$PREFIX" \
    -Dfreetype=enabled -Dglib=disabled -Dgobject=disabled \
    -Dcairo=disabled -Dicu=disabled \
    -Dtests=disabled -Ddocs=disabled -Dintrospection=disabled \
    2>&1 | tail -2
  ninja -C build-$ABI 2>&1 | tail -1
  ninja -C build-$ABI install 2>&1 | tail -1

  # ---- libass ----
  echo "== [$ABI] libass =="
  cd "$WORK/libass"
  rm -rf build-$ABI
  meson setup build-$ABI \
    --cross-file "$WORK/cross-$ABI.meson" \
    --buildtype release \
    --prefix "$PREFIX" \
    -Dfontconfig=disabled -Dtest=false -Drequire-system-font-provider=false \
    2>&1 | tail -2
  ninja -C build-$ABI 2>&1 | tail -1
  ninja -C build-$ABI install 2>&1 | tail -1

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
    -Dopensles=enabled \
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
