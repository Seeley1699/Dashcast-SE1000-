#!/bin/sh
# build-dashcastd.sh — 用 zig 交叉编译 CP 侧 dashcastd (aarch64-linux-gnu)
#
# 依赖（已备好）：
#   sysroot/include/wayland/*.h                                 wayland 头 + ivi 协议头
#   sysroot/include/wayland/ivi-application-client-protocol.c   ivi 接口表实现（必须一起编，否则
#                                                               undefined symbol: ivi_*_interface）
#   sysroot/lib/libwayland-client.so.0                          aarch64 的 wayland-client
#   third_party/lib/libopenh264.a                               openh264 静态库（软解 H.264）
#   zig 编译器（ZIG 环境变量或脚本内指定）
#
# 用法：
#   ./build-dashcastd.sh [zig路径]
#
# 注意 1：zig 是 Windows 程序，路径必须是 F:/... 这种正斜杠 Windows 路径，
#         直接给它 /f/... 的 MSYS 路径会找不到头文件。
#
# 注意 2：openh264 是 C++ 写的，静态链进来会带出 __cxxabiv1 / __gxx_personality，
#         所以链接那一步必须用 `zig c++`（它会自动补 C++ 运行时）。
#         编译阶段仍然分家：.c 用 cc，.cpp 用 c++。
#
# 产物是自包含的单文件：只依赖 CP 上本来就有的
#   libwayland-client.so.0 / libc / libm / libpthread / librt / libdl，
# 不用再往 CP 上额外推 libopenh264.so。

ZIG="${ZIG:-$1}"
ZIG="${ZIG:-E:/cpbuild/zig-windows-x86_64-0.13.0/zig.exe}"

HERE_MSYS="$(cd "$(dirname "$0")" && pwd)"
# /f/Documents/... → F:/Documents/...
HERE="$(echo "$HERE_MSYS" | sed 's#^/\([a-zA-Z]\)/#\1:/#')"
SYSROOT="$HERE/sysroot"
TMP="$HERE/.build"

echo "[build] zig: $ZIG"
echo "[build] here: $HERE"

rm -rf "$TMP"; mkdir -p "$TMP"

INC="-I$SYSROOT/include -I$SYSROOT/include/wayland -I$HERE/../dashcast_proto \
     -I$HERE/third_party/wels -I$HERE"

# ① C 部分
"$ZIG" cc -target aarch64-linux-gnu -O2 -Wall $INC \
    -c "$HERE/dashcastd.c"                                          -o "$TMP/dashcastd.o" || exit 1
"$ZIG" cc -target aarch64-linux-gnu -O2 -Wall $INC \
    -c "$HERE/h264rx.c"                                             -o "$TMP/h264rx.o" || exit 1
"$ZIG" cc -target aarch64-linux-gnu -O2 -Wall $INC \
    -c "$SYSROOT/include/wayland/ivi-application-client-protocol.c" -o "$TMP/ivi.o" || exit 1

# ② C++ 部分（openh264 的薄封装）
"$ZIG" c++ -target aarch64-linux-gnu -O2 -Wall $INC \
    -c "$HERE/h264shim.cpp"                                         -o "$TMP/h264shim.o" || exit 1

# ③ 链接：静态吃进 openh264，动态只留系统库
"$ZIG" c++ -target aarch64-linux-gnu -O2 \
    "$TMP/dashcastd.o" "$TMP/h264rx.o" "$TMP/ivi.o" "$TMP/h264shim.o" \
    "$HERE/third_party/lib/libopenh264.a" \
    -L"$SYSROOT/lib" \
    -lwayland-client -lpthread -lrt -lm -ldl \
    -o "$HERE/dashcastd" -Wl,-s

if [ $? -eq 0 ]; then
    rm -rf "$TMP"
    echo "[build] 成功: $HERE/dashcastd （自包含，无需额外 .so）"
    echo "[build] 记得同步 APK 里的资产："
    echo "         base64 -w0 dashcastd > dashcastd.b64"
    echo "         cp dashcastd.b64 ../ap/app/src/main/assets/dashcastd.b64"
else
    echo "[build] 失败"
    exit 1
fi
