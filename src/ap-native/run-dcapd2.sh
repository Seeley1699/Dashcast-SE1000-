#!/usr/bin/env bash
# run-dcapd2.sh — 把 dcapd2 推上车，自动取目标 App 的窗口矩形，然后跑起来
#
# 用法：
#   ./run-dcapd2.sh <包名> [cp_host] [cp_port] [maxFps]
# 例：
#   ./run-dcapd2.sh com.huawei.himovceie
#
# 前提：App 已经在中控前台（我们要抓的就是它现在那块矩形）
set -u

PKG=${1:?用法: run-dcapd2.sh <包名> [cp_host] [cp_port] [maxFps]}
HOST=${2:-198.18.34.2}
PORT=${3:-8444}
FPS=${4:-30}
ADB=${ADB:-adb}
HERE=$(cd "$(dirname "$0")" && pwd)

LIBCXX="C:/Users/94790/AppData/Local/Android/Sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"

echo "== 1/5 读主屏尺寸"
SIZE=$($ADB shell wm size | tr -d '\r' | awk -F': ' '/Physical size/{print $2}' | head -1)
DISP_W=${SIZE%x*}
DISP_H=${SIZE#*x}
if [ -z "$DISP_W" ] || [ -z "$DISP_H" ]; then
    echo "读不到 wm size（设备没连？）"; exit 1
fi
echo "   ${DISP_W}x${DISP_H}"

echo "== 2/5 找 $PKG 的窗口矩形"
DUMP=$($ADB shell dumpsys window windows | tr -d '\r')
FRAME=$(echo "$DUMP" | awk -v pkg="$PKG" '
    /Window\{/ { inblk = (index($0, pkg) > 0) }
    inblk && /mFrame=\[/ { print; exit }
')
if [ -z "$FRAME" ]; then
    echo "   dumpsys 里没有 $PKG 的窗口 —— 它现在不在前台？"
    echo "   （先在中控把 App 打开，再跑这个脚本）"
    exit 1
fi
NUM=$(echo "$FRAME" | grep -o '[0-9-]\+' | head -4 | tr '\n' ' ')
set -- $NUM
X1=$1; Y1=$2; X2=$3; Y2=$4
SRC_X=$X1
SRC_Y=$Y1
SRC_W=$((X2 - X1))
SRC_H=$((Y2 - Y1))
if [ "$SRC_W" -le 0 ] || [ "$SRC_H" -le 0 ]; then
    echo "   解析出的矩形不合法: $FRAME"; exit 1
fi
echo "   $SRC_X,$SRC_Y ${SRC_W}x${SRC_H}   ← $FRAME"

echo "== 3/5 推送二进制"
$ADB push "$HERE/dcapd2" /data/local/tmp/dcapd2 >/dev/null || exit 1
$ADB shell chmod 755 /data/local/tmp/dcapd2
if ! $ADB shell "test -f /data/local/tmp/libc++_shared.so" >/dev/null 2>&1; then
    echo "   推 libc++_shared.so（1.8MB，只需一次）"
    $ADB push "$LIBCXX" /data/local/tmp/libc++_shared.so >/dev/null || exit 1
fi

echo "== 4/5 启动"
$ADB shell "pkill -f '/data/local/tmp/dcapd2'" >/dev/null 2>&1
$ADB shell "cd /data/local/tmp && LD_LIBRARY_PATH=/data/local/tmp \
    setsid ./dcapd2 $DISP_W $DISP_H $SRC_X $SRC_Y $SRC_W $SRC_H 1920 532 $HOST $PORT $FPS \
    > dcapd2.log 2>&1 < /dev/null &" >/dev/null 2>&1

sleep 2
echo "== 5/5 前 20 行日志"
$ADB shell "tail -20 /data/local/tmp/dcapd2.log"

cat <<EOF

—— 接下来 ——
看实时统计：   $ADB shell "tail -f /data/local/tmp/dcapd2.log"
停掉：         $ADB shell "pkill -f '/data/local/tmp/dcapd2'"
EOF
