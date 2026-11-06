#!/bin/bash
for d in libass mpv; do
  echo "== $d =="
  cd /opt/libmpv-lgpl/$d 2>/dev/null || continue
  if [ -d build-arm64-v8a ]; then
    ninja -C build-arm64-v8a -j1 2>&1 | grep -E 'error:|Error ' | head -3
  else
    echo "no build dir"
  fi
done
