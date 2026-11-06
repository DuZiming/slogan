#!/bin/bash
cd /opt/libmpv-lgpl
for url in \
  "https://download.savannah.gnu.org/releases/freetype/freetype-2.13.3.tar.gz" \
  "https://github.com/fribidi/fribidi/releases/download/v1.0.16/fribidi-1.0.16.tar.gz" \
  "https://github.com/libass/libass/releases/download/0.17.3/libass-0.17.3.tar.gz"
do
  name=$(basename "$url")
  echo -n "$name: "
  curl -sL --max-time 60 -o "/tmp/$name" "$url" 2>/dev/null
  size=$(stat -c%s "/tmp/$name" 2>/dev/null || echo 0)
  if [ "$size" -gt 100000 ]; then
    echo "OK ($size bytes)"
    rm -f "/tmp/$name"
  else
    echo "FAIL ($size bytes)"
  fi
done
