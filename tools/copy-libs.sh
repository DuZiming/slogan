#!/bin/bash
set -e
DST="/mnt/c/Users/admin/Desktop/slogan/.claude/worktrees/player-kernel/player/src/main/jniLibs"
for ABI in arm64-v8a armeabi-v7a; do
  mkdir -p "$DST/$ABI"
  P="/opt/libmpv-lgpl/prefix-$ABI/lib"
  for f in libavcodec libavformat libavutil libavfilter libswscale libswresample libplacebo libharfbuzz libass libfreetype libfribidi; do
    [ -f "$P/$f.so" ] && cp "$P/$f.so" "$DST/$ABI/"
  done
  cp "/opt/libmpv-lgpl/mpv/build-$ABI/libmpv.so" "$DST/$ABI/"
  echo "== $ABI: $(ls $DST/$ABI/*.so | wc -l) files"
done
echo "v0.40 LGPL" > "$DST/libmpv-lgpl-version.txt"
