$ErrorActionPreference = 'Continue'

# ============================================================
#  Cluster dashboard screenshot (single capture)
#
#  Chain : Windows --adb--> AP(192.168.10.209)
#                 --com.hermes.dashcast.CPCMD--> CP(198.18.34.2)
#                 --LayerManagerControl dump surface--> dashboard
#
#  Read-only. No service is stopped, restarted or reconfigured.
#  Scratch files live only on tmpfs:
#     CP : /run/shot.bmp            (removed inline, tmpfs cleared on reboot)
#     AP : /data/local/tmp/dshcap_shot  (removed immediately)
#  Local raw BMP goes to %TEMP% and is deleted after PNG conversion.
#  Nothing persists after the run.
# ============================================================

$DEVICE = '192.168.10.209:5555'
$CPCMD  = 'com.hermes.dashcast.CPCMD'
$CP_OUT = '/data/user/11/com.hermes.dashcast/files/cpcmd.out'
$AP_DIR = '/data/local/tmp/dshcap_shot'
$AP_PID = '/data/local/tmp/dshcap_shot.pid'

function Say($msg, $color) {
    if ($color) { Write-Host $msg -ForegroundColor $color } else { Write-Host $msg }
}

# ---------------- locate adb ----------------
$adb = $null
foreach ($c in @(
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
    (Join-Path $env:ProgramFiles 'Android\Sdk\platform-tools\adb.exe'),
    (Join-Path ${env:ProgramFiles(x86)} 'Android\Sdk\platform-tools\adb.exe')
)) { if (Test-Path $c) { $adb = $c; break } }
if (-not $adb) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source }
}
if (-not $adb) { Say "ERROR: adb.exe not found" Red; Say "  install platform-tools or pass adb path" ; exit 1 }

# ---------------- device ----------------
$devList = (& $adb devices 2>&1 | Out-String)
if ($devList -notmatch [regex]::Escape($DEVICE) + '\s+device') {
    Say "ERROR: device not connected ($DEVICE)" Red
    Say "  try: `"$adb`" connect $DEVICE"
    exit 1
}

# ---------------- target ----------------
$desktop = [Environment]::GetFolderPath('Desktop')
if (-not (Test-Path $desktop)) { $desktop = Join-Path $env:USERPROFILE 'Desktop' }
$port  = Get-Random -Minimum 9200 -Maximum 9399
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$png   = Join-Path $desktop "dashboard_$stamp.png"

Say ""
Say "=== cluster dashboard screenshot ===" Cyan
Say "  device : $DEVICE"
Say "  output : $png"
Say ""

# ---------------- AP relay ----------------
& $adb -s $DEVICE shell "for p in `$(cat $AP_PID 2>/dev/null); do kill -9 `$p 2>/dev/null; done; rm -rf $AP_DIR; mkdir -p $AP_DIR; rm -f $AP_PID" 2>&1 | Out-Null
& $adb -s $DEVICE shell "nohup busybox nc -l -p $port > $AP_DIR/s.bmp 2>/dev/null & echo `$! > $AP_PID" 2>&1 | Out-Null
Start-Sleep -Milliseconds 900

$chk = (& $adb -s $DEVICE shell "netstat -tnl 2>/dev/null | grep -c ':$port'" 2>&1 | Out-String).Trim()
Say ("  port=$port cpcmd=$CPCMD")

if ($chk -notmatch '^[1-9]') {
    Say "ERROR: AP relay listener failed to start" Red
    & $adb -s $DEVICE shell "rm -rf $AP_DIR; rm -f $AP_PID" 2>&1 | Out-Null
    exit 1
}

# ---------------- CP capture + stream ----------------
Say "  capturing ... " -NoNewline
# CP side script: loaded from an external ASCII-only template.
# (2026-11-08) Chinese text in the bash heredoc got UTF-8->GBK mojibake;
# the corrupted quote broke the `echo "SID=..."` line, leaving $SID empty,
# so the script fell back to the first surface (layer 10 / factory dashboard),
# which is why every screenshot came back as the factory cluster UI.
# Keeping the template in a separate file also avoids nested here-string issues.
$cpTemplate = Join-Path $PSScriptRoot 'dashboard-shot-cp.sh'
if (-not (Test-Path $cpTemplate)) { Say "ERROR: missing CP template $cpTemplate" Red; exit 1 }
$cpScript = (Get-Content $cpTemplate -Raw -Encoding UTF8).Replace('%%PORT%%', "$port")

& $adb -s $DEVICE shell "rm -f $CP_OUT" 2>&1 | Out-Null
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($cpScript))
& $adb -s $DEVICE shell "am broadcast -a $CPCMD --es cmd 'b64:$b64' --receiver-include-background" 2>&1 | Out-Null

$deadline = (Get-Date).AddSeconds(50)
$raw = ''
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 350
    $raw = (& $adb -s $DEVICE shell "cat $CP_OUT 2>/dev/null" 2>&1 | Out-String)
    # **只看 `--- output ---` 之后的内容**（2026-11-08 修）
    # cpcmd.out 的结构是：
    #   ACTION=...
    #   CMD=<整条脚本>        ← 这里就含 SID/SZ/__SHOT_END__ 字样
    #   --- output ---
    #   <命令的真实输出>
    # 原来直接对 $raw 做 -match，匹配到的是 CMD 回显，
    # 于是 CP 还没跑完就 break，pull 到的是上一轮的旧帧（表现为"截图永远是原厂仪表"）。
    $mk = '--- output ---'
    $mi = $raw.IndexOf($mk)
    if ($mi -ge 0) {
        $outPart = $raw.Substring($mi + $mk.Length)
        if ($outPart -match '__SHOT_END__') { break }
    }
}
$mk2 = '--- output ---'
$mi2 = $raw.IndexOf($mk2)
$outputOnly = if ($mi2 -ge 0) { $raw.Substring($mi2 + $mk2.Length) } else { '' }
if ($outputOnly -notmatch '__SHOT_END__') {
    Say "FAILED (CP no response)" Red
    & $adb -s $DEVICE shell "for p in `$(cat $AP_PID 2>/dev/null); do kill -9 `$p 2>/dev/null; done; rm -rf $AP_DIR; rm -f $AP_PID" 2>&1 | Out-Null
    exit 1
}
# 把 CP 侧报告的 SID / SZ 打出来 —— 这是判断"抓到哪一层"的唯一直接证据。
# 之前只看 remote health 的 w/h/r 三个数字，无法区分抓的是 layer 10 还是 layer 99。
$sidLine = ($outputOnly -split "`n" | Where-Object { $_ -match '^SID=' } | Select-Object -First 1)
$szLine  = ($outputOnly -split "`n" | Where-Object { $_ -match '^SZ=' }  | Select-Object -First 1)
if ($sidLine) { Say ("  cp: " + $sidLine.Trim()) }
if ($szLine)  { Say ("  cp: " + $szLine.Trim()) }
Say "done"

# ---------------- pull ----------------
Start-Sleep -Seconds 2
& $adb -s $DEVICE shell "for p in `$(cat $AP_PID 2>/dev/null); do kill -9 `$p 2>/dev/null; done; rm -f $AP_PID" 2>&1 | Out-Null

$szTxt = (& $adb -s $DEVICE shell "stat -c %s $AP_DIR/s.bmp 2>/dev/null || echo 0" 2>&1 | Out-String).Trim()
if ($szTxt -notmatch '^\d+$' -or [int]$szTxt -eq 0) {
    Say "ERROR: frame fetch failed (size=$szTxt)" Red
    & $adb -s $DEVICE shell "rm -rf $AP_DIR" 2>&1 | Out-Null
    exit 1
}

$localRaw = Join-Path $env:TEMP "dshcap_$stamp.bmp"
Say ("  transfer {0:N2} MB ... " -f ([int]$szTxt / 1MB)) -NoNewline
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $adb -s $DEVICE pull "$AP_DIR/s.bmp" $localRaw 2>&1 | Out-Null
$ErrorActionPreference = $prevEap
& $adb -s $DEVICE shell "rm -rf $AP_DIR" 2>&1 | Out-Null
Say "done"

# ---------------- convert ----------------
try {
    Add-Type -AssemblyName System.Drawing -ErrorAction Stop
    $img = [System.Drawing.Image]::FromFile($localRaw)
    $bmp = New-Object System.Drawing.Bitmap($img.Width, $img.Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.Color]::Black)
    $g.DrawImageUnscaled($img, 0, 0)
    $bmp.Save($png, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose(); $img.Dispose()
} catch {
    Say "ERROR: PNG conversion failed - $_" Red
    if (Test-Path $localRaw) { Remove-Item $localRaw -Force -ErrorAction SilentlyContinue }
    exit 1
}
if (Test-Path $localRaw) { Remove-Item $localRaw -Force -ErrorAction SilentlyContinue }

# ---------------- report ----------------
Say ""
Say "  saved: $png" Green
if (Test-Path $png) {
    Say ("  size : {0:N1} KB" -f ((Get-Item $png).Length / 1KB))
}

Say ""
Say "  remote health:" Gray
$hScript = 'echo "w=$(systemctl is-active weston 2>/dev/null)"; echo "h=$(pgrep -f cluster-hmi-p171 2>/dev/null | wc -l)"; echo "r=$(ls -d /run/shot.bmp 2>/dev/null | wc -l)"'
& $adb -s $DEVICE shell "rm -f $CP_OUT" 2>&1 | Out-Null
$hb64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($hScript))
& $adb -s $DEVICE shell "am broadcast -a $CPCMD --es cmd 'b64:$hb64' --receiver-include-background" 2>&1 | Out-Null
$hdead = (Get-Date).AddSeconds(25)
$hraw = ''
while ((Get-Date) -lt $hdead) {
    Start-Sleep -Milliseconds 350
    $hraw = (& $adb -s $DEVICE shell "cat $CP_OUT 2>/dev/null" 2>&1 | Out-String)
    if ($hraw -match '--- output ---') { break }
}
$hi = $hraw.IndexOf('--- output ---')
if ($hi -ge 0) {
    foreach ($l in ($hraw.Substring($hi + 14).Trim() -split "`n")) {
        if ($l.Trim()) { Say "    $($l.Trim())" Gray }
    }
}
& $adb -s $DEVICE shell "rm -f $CP_OUT" 2>&1 | Out-Null
Say ""

# ---------------- open ----------------
if (Test-Path $png) { Start-Process $png }
