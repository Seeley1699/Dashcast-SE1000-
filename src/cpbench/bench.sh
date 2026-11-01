#!/usr/bin/env bash
# ============================================================================
# cpbench —— 仪表投屏编码模式对比压测（安全版）
#
# 设计前提：**任何操作都不允许写持久分区**。
#   · 二进制只在 /run（tmpfs，CP 重启即消失）
#   · render order 只改运行时状态（weston.ini 一个字节都不碰）
#   · CP 上常驻一个 45 秒心跳 watchdog，宿主机一断就自动还原
#   · preflight 每一条都是 fail-closed：有一条不满足直接退出，不上车
#
# 用法：
#   ./bench.sh preflight            只做安全检查，不碰任何东西
#   ./bench.sh arm                  装 watchdog + 记录基线
#   ./bench.sh measure 30           测当前模式的 30 秒数据
#   ./bench.sh restore              还原（幂等，可重复跑）
#   ./bench.sh verify               核对是否回到原样
#   ./bench.sh all 30               arm → measure → restore → verify
#
# 环境：Git Bash + adb，设备已 adb connect。
# ============================================================================
set -uo pipefail

ADB="${ADB:-adb}"
ACTION="com.hermes.dashcast.CPCMD"
TAG="DashcastDiag"
STATE_DIR="/run/cpbench"
WD_TIMEOUT=45          # 心跳多久没更新就自动还原
HERE="$(cd "$(dirname "$0")" && pwd)"
LOG="$HERE/_bench.log"

say()  { printf '%s\n' "$*" | tee -a "$LOG"; }
die()  { printf '\n[中止] %s\n' "$*" | tee -a "$LOG" >&2; exit 1; }

# ---------------------------------------------------------------- CP 命令通道
# 命令 base64 后传，避开 adb shell / am broadcast 的引号问题。
# 输出走 App 私有目录 —— 这台 ROM：logcat 吞第三方日志、/data/local/tmp 被
# SELinux 拦、run-as 默认查 user 0 而 App 跑在 user 11。直读真实路径最稳。
CP_OUT="/data/user/11/com.hermes.dashcast/files/cpcmd.out"

cp_run() {
    local cmd="$1" wait="${2:-3}" b64 old i now
    b64="$(printf '%s' "$cmd" | base64 -w0)"
    old="$("$ADB" shell "cat $CP_OUT 2>/dev/null" | md5sum | cut -d' ' -f1)"
    "$ADB" shell "am broadcast -n com.hermes.dashcast/.DiagReceiver \
        -a com.hermes.dashcast.CPCMD --es cmd b64:$b64" >/dev/null 2>&1
    i=0
    while [ "$i" -lt "$wait" ]; do
        sleep 1
        i=$((i + 1))
        now="$("$ADB" shell "cat $CP_OUT 2>/dev/null" | md5sum | cut -d' ' -f1)"
        if [ "$now" != "$old" ]; then
            "$ADB" shell "cat $CP_OUT" 2>/dev/null \
                | sed -n '/^--- output ---$/,$p' | tail -n +2
            return 0
        fi
    done
    echo "(超时 ${wait}s，CP 没回应)"
}

# weston 的 wayland socket 每次开机可能变号，先探测
CPENV='export XDG_RUNTIME_DIR=/etc/weston/weston_xdg_runtime_dir; export WAYLAND_DISPLAY=$(ls /etc/weston/weston_xdg_runtime_dir 2>/dev/null | grep -oE "wayland-[0-9]+" | head -1); [ -z "$WAYLAND_DISPLAY" ] && export WAYLAND_DISPLAY=wayland-1; '

# ---------------------------------------------------------------- 前置检查
preflight() {
    say "=== cpbench preflight $(date '+%F %T') ==="

    # 1. 设备在线
    command -v "$ADB" >/dev/null 2>&1 || die "找不到 adb"
    local devs; devs="$("$ADB" devices | grep -c 'device$')"
    [ "$devs" -ge 1 ] || die "没有在线的 adb 设备（先 adb connect <车机IP>:5555）"
    say "  [1/7] adb 设备在线 ✓"

    # 2. App 装着、CPCMD 通道通
    local ping; ping="$(cp_run 'echo CP_OK; id -u' 4)"
    echo "$ping" | grep -q CP_OK || die "CP 通道不通（App 没装新版？广播被拦？）"
    say "  [2/7] CP 命令通道 ✓  uid=$(echo "$ping" | grep -E '^[0-9]+$' | head -1)"

    # 3. /run 必须是 tmpfs —— 这条决定"重启是否一定干净"
    local fs; fs="$(cp_run 'mount | grep -E " /run " ' 2>/dev/null)"
    if echo "$fs" | grep -qi tmpfs; then
        say "  [3/7] /run 是 tmpfs ✓（CP 一重启，我们的二进制自动消失）"
    else
        say "  [3/7] /run 挂载信息："
        say "        ${fs:-（读不到）}"
        die "/run 不是 tmpfs —— 二进制会留在持久分区，重启不保证干净。停止。"
    fi

    # 4. 没有开机自启 —— 否则重启后它自己又起来
    local boot; boot="$(cp_run 'grep -rl dashcastd /etc/init.d /etc/rc.local /etc/systemd /lib/systemd /etc/profile.d 2>/dev/null | head -5; echo ---; ls /etc/init.d 2>/dev/null | grep -i dash' 5)"
    if echo "$boot" | grep -qi dashcastd; then
        say "  [4/7] 发现自启项："
        say "$boot"
        die "CP 上有 dashcastd 开机自启 —— 先清掉再测，否则重启也回不到原样。"
    fi
    say "  [4/7] 无开机自启 ✓"

    # 5. 记录基线 render order（当前如果已经含 99，就把它剥掉当基线）
    # 输出形如 "- layer render order:   10(0xa), 99(0x63)" —— 必须先把
    # "(0x..)" 括号里的十六进制去掉，否则会把 0xa 的 "0" 当成一个图层号，
    # 拼出 "10 0" 这种非法表，设下去仪表直接黑。
    local raw base
    raw="$(cp_run "${CPENV}LayerManagerControl get screen 0 2>/dev/null | grep -i 'render order'" 4)"
    base="$(echo "$raw" \
        | sed 's/(0x[0-9a-fA-F]*)//g' \
        | sed 's/.*render order[: ]*//' \
        | grep -oE '[0-9]+' \
        | grep -v '^99$' | tr '\n' ' ' | sed 's/ $//')"
    [ -z "$base" ] && base="10"
    say "  [5/7] 基线 render order = [$base]   (原始: ${raw:-读不到})"
    printf '%s\n' "$base" > "$HERE/.base_order"

    # 6. 持久分区指纹 —— 测完要比对，自证没写过
    local sig; sig="$(cp_run 'md5sum /etc/xdg/weston/weston.ini 2>/dev/null; ls -la /etc/xdg/weston/weston.ini 2>/dev/null' 4)"
    printf '%s\n' "$sig" > "$HERE/.persist_before"
    say "  [6/7] 持久配置指纹已存 ✓  $(echo "$sig" | head -1)"

    # 7. 人工确认：必须停稳
    say "  [7/7] 车辆必须**停稳熄火前**再测 —— 测试期间仪表可能黑 1~2 秒。"
    say ""
    say "preflight 通过。"
}

# ---------------------------------------------------------------- 装 watchdog
arm() {
    local base; base="$(cat "$HERE/.base_order" 2>/dev/null || echo 10)"
    say "=== 装 CP watchdog（基线 [$base]，心跳 ${WD_TIMEOUT}s）==="

    cp_run "mkdir -p $STATE_DIR; printf '%s' '$base' > $STATE_DIR/base; touch $STATE_DIR/hb; echo ARMED" 4 >/dev/null

    # 关键：这个脚本在 CP 上脱离 SSH 会话独立跑。宿主机失联 / adb 掉线 /
    # 我们这边崩了，都会因为心跳过期而自动把仪表还原。
    #
    # 只改 render order，**绝不杀进程**：/run/dashcastd 是 EVCC 自己的守护
    # （pid 3097，77KB），杀了会顺带停掉用户正在用的投屏，而且删了二进制
    # 还得等下次投屏重新推。摘掉 99 出渲染表，屏幕就已经回原厂了，够了。
    cp_run "$(cat <<EOF
mkdir -p $STATE_DIR
cat > $STATE_DIR/wd.sh <<'WDSH'
#!/bin/sh
while true; do
  now=\$(date +%s)
  hb=\$(stat -c %Y $STATE_DIR/hb 2>/dev/null || echo 0)
  if [ \$((now - hb)) -gt $WD_TIMEOUT ]; then
    export XDG_RUNTIME_DIR=/etc/weston/weston_xdg_runtime_dir
    export WAYLAND_DISPLAY=\$(ls /etc/weston/weston_xdg_runtime_dir 2>/dev/null | grep -oE 'wayland-[0-9]+' | head -1)
    [ -z "\$WAYLAND_DISPLAY" ] && export WAYLAND_DISPLAY=wayland-1
    LayerManagerControl set screen 0 render order \$(cat $STATE_DIR/base) 2>/dev/null
    echo "watchdog fired \$(date) -> order=\$(cat $STATE_DIR/base)" >> $STATE_DIR/wd.log
    exit 0
  fi
  sleep 5
done
WDSH
chmod +x $STATE_DIR/wd.sh
nohup $STATE_DIR/wd.sh >/dev/null 2>&1 &
sleep 1
echo "WD_ARMED pid=\$(pidof wd.sh | head -1)"
EOF
)" 4 | tee -a "$LOG"
}

# ---------------------------------------------------------------- 采样
# 全部用"两点差分"，避免读绝对值
cp_stat() {   # 输出: rx_bytes sys_jiffies idle_jiffies
    cp_run "awk 'NR==1{rx=0} {print}' /dev/null 2>/dev/null; \
            rx=0; for f in /sys/class/net/*/statistics/rx_bytes; do rx=\$((rx+\$(cat \$f 2>/dev/null || echo 0))); done; \
            set -- \$(head -1 /proc/stat); \
            echo \"RX=\$rx USER=\$2 NICE=\$3 SYS=\$4 IDLE=\$5 IOW=\$6 IRQ=\$7 SIRQ=\$8\"; \
            grep -c . /proc/stat >/dev/null" 3
}

measure() {
    local secs="${1:-30}"
    local base; base="$(cat "$HERE/.base_order" 2>/dev/null || echo 10)"
    say ""
    say "=== 采样 ${secs}s（当前界面上的编码模式）==="

    # 先打一拍心跳
    cp_run "touch $STATE_DIR/hb" 1 >/dev/null

    local t0 t1
    t0="$(cp_stat)"
    local ap0; ap0="$("$ADB" shell "cat /proc/stat | head -1; cat /proc/net/dev | grep -E 'eth0|wlan0' | head -2" 2>/dev/null)"
    say "  t0: $(echo "$t0" | grep RX= | head -1)"

    local i=0
    while [ "$i" -lt "$secs" ]; do
        sleep 5
        cp_run "touch $STATE_DIR/hb" 1 >/dev/null     # 续命
        i=$((i + 5))
        printf '  ... %ss\n' "$i" | tee -a "$LOG"
    done

    t1="$(cp_stat)"
    local ap1; ap1="$("$ADB" shell "cat /proc/stat | head -1" 2>/dev/null)"
    say "  t1: $(echo "$t1" | grep RX= | head -1)"

    # 算差分
    python - "$secs" "$t0" "$t1" <<'PY' | tee -a "$LOG"
import re, sys
secs = float(sys.argv[1])
def kv(s):
    d = {}
    for m in re.finditer(r'(\w+)=(\d+)', s):
        d[m.group(1)] = int(m.group(2))
    return d
a, b = kv(sys.argv[2]), kv(sys.argv[3])
if not a or not b:
    print("  （采样解析失败，原始：", sys.argv[2], sys.argv[3], "）"); raise SystemExit
rx = (b.get('RX', 0) - a.get('RX', 0)) / secs
def busy(x):
    t = sum(x.get(k, 0) for k in ('USER','NICE','SYS','IDLE','IOW','IRQ','SIRQ'))
    return t, x.get('IDLE', 0) + x.get('IOW', 0)
t0, i0 = busy(a); t1, i1 = busy(b)
cpu = 100.0 * (1 - (i1 - i0) / max(1, (t1 - t0)))
print()
print("  ┌────────────────────────────────────────")
print(f"  │ CP 接收带宽 : {rx/1024/1024:8.2f} MB/s   ({rx*8/1e6:7.1f} Mbps)")
print(f"  │ CP 整机 CPU : {cpu:7.1f} %")
print("  └────────────────────────────────────────")
PY
}

# ---------------------------------------------------------------- 还原
restore() {
    local base; base="$(cat "$HERE/.base_order" 2>/dev/null || echo 10)"
    say ""
    say "=== 还原（基线 [$base]）==="

    # 1. 先 disarm watchdog（续上心跳再杀它自己）
    cp_run "mkdir -p $STATE_DIR; touch $STATE_DIR/hb; sleep 1; pkill -f $STATE_DIR/wd.sh; echo WD_KILLED" 4 | tee -a "$LOG"

    # 2. 摘 render order 里的 99（有则摘，没有也执行一遍保证幂等）
    #    —— 只改渲染表，不动任何进程、不删任何文件：
    #    /run/dashcastd 是 EVCC 自己的守护，删了下次投屏还得重推。
    cp_run "${CPENV}LayerManagerControl set screen 0 render order $base 2>/dev/null; sleep 1; LayerManagerControl get screen 0 2>/dev/null | grep -i 'render order'" 5 | tee -a "$LOG"

    # 3. 清我们自己建的看门狗目录（只删这一个，别的一概不碰）
    cp_run "rm -rf $STATE_DIR; echo CLEANED" 3 | tee -a "$LOG"

    # 4. AP 侧收尾（dcapd2 是我们自己的，可以停）
    "$ADB" shell "pkill -9 -x dcapd2; rm -f /data/local/tmp/dcapd2.bg /data/local/tmp/dcapd2.pause" >/dev/null 2>&1
    say "  AP 侧 dcapd2 已停"
}

# ---------------------------------------------------------------- 核对
verify() {
    local base; base="$(cat "$HERE/.base_order" 2>/dev/null || echo 10)"
    say ""
    say "=== 核对 ==="
    local r; r="$(cp_run "${CPENV}LayerManagerControl get screen 0 2>/dev/null | grep -i 'render order'; \
        ls $STATE_DIR 2>/dev/null || echo BENCH_CLEAN; \
        echo -n 'dashcastd='; pidof dashcastd || echo none" 5)"
    say "$r"

    if echo "$r" | grep -qiE 'render order'; then
        # 输出形如 "- layer render order:   10(0xa), 99(0x63)"
        if echo "$r" | sed 's/(0x[0-9a-fA-F]*)//g' | grep -qE '(^|[^0-9])99([^0-9]|$)'; then
            say "  ✗ render order 里还有 99 —— 仪表上可能还挂着我们的图层"
        else
            say "  ✓ render order 干净（不含 99）"
        fi
    fi
    echo "$r" | grep -q BENCH_CLEAN && say "  ✓ /run/cpbench 已清空" \
        || say "  ✗ /run/cpbench 还有残留"
    say "  · dashcastd 状态：$(echo "$r" | grep -oE 'dashcastd=[a-z0-9]+')（EVCC 自己的守护，活着是对的）"

    # 持久分区没被动过
    local after; after="$(cp_run 'md5sum /etc/xdg/weston/weston.ini 2>/dev/null; ls -la /etc/xdg/weston/weston.ini 2>/dev/null' 4)"
    if [ -f "$HERE/.persist_before" ]; then
        if [ "$after" = "$(cat "$HERE/.persist_before")" ]; then
            say "  ✓ 持久配置未改动（weston.ini 指纹一致）"
        else
            say "  ✗ 持久配置变了！测前："
            say "$(cat "$HERE/.persist_before")"
            say "     测后：$after"
        fi
    fi
    say ""
    say "最后一步：断电重启一次，再跑 ./bench.sh verify 确认开机也是原样。"
}

case "${1:-}" in
    preflight) preflight ;;
    arm)       arm ;;
    measure)   measure "${2:-30}" ;;
    restore)   restore ;;
    verify)    verify ;;
    all)       preflight && arm && measure "${2:-30}" && restore && verify ;;
    *)         sed -n '2,25p' "$0" ;;
esac
