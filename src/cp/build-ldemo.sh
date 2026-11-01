#!/bin/sh
# build-ldemo.sh —— 演示模式构建（带 H.264 解码，只用于"看效果"）
set -e
ZIG="${ZIG:-E:/cpbuild/zig-windows-x86_64-0.13.0/zig.exe}"
HERE_MSYS="$(cd "$(dirname "$0")" && pwd)"
HERE="$(echo "$HERE_MSYS" | sed 's#^/\([a-zA-Z]\)/#\1:/#')"
"$ZIG" cc \
    -target aarch64-linux-gnu -O2 -DDC_DEMO \
    -I"$HERE/sysroot/include" -I"$HERE/sysroot/include/wayland" \
    -I"$HERE/../dashcast_proto" -I"$HERE" -I"$HERE/third_party/wels" \
    "$HERE/dashcastd.c" "$HERE/h264shim.cpp" \
    "$HERE/sysroot/include/wayland/ivi-application-client-protocol.c" \
    "$HERE/third_party/lib/libopenh264.so.2.2.0" \
    -L"$HERE/sysroot/lib" -lwayland-client -lpthread -lrt -lm \
    -o "$HERE/ldemo" -Wl,-s
echo "[build] ok: $HERE/ldemo"
