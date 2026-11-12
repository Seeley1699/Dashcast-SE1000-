<#
.SYNOPSIS
    Dump 仪表渲染结果（DashRenderer 的原始输出）为 PNG。

.DESCRIPTION
    走 DashDumpActivity → ImageReader → PNG → base64 → logcat 的链路，
    拿到的是 DashRenderer 直接绘制的结果，不经过 dcapd2 / CP。

    与 dashboard-shot.ps1 的区别：
        dashboard-shot.ps1  → 抓 CP 侧仪表**最终显示**的像素（含真地图）
        本脚本              → 抓 DashRenderer 的**原始渲染**（示意路网）

    两者都要，用来分别验证「渲染层」和「链路层」。

.EXAMPLE
    .\dump-dash.ps1
    .\dump-dash.ps1 -OutFile docs\visual-baseline\after.png
#>
param(
    [string]$Device  = '192.168.10.209:5555',
    [string]$OutFile = '',
    [string]$Pkg     = 'com.hermes.dashcast',
    [string]$Activity= '.DashDumpActivity',
    [string]$Tag     = 'DashPerf',
    [int]$WaitSec    = 14
)

$ErrorActionPreference = 'Continue'

# ---------------- locate adb ----------------
$adb = $null
foreach ($c in @(
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
    (Join-Path $env:ProgramFiles 'Android\Sdk\platform-tools\adb.exe')
)) { if (Test-Path $c) { $adb = $c; break } }
if (-not $adb) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source }
}
if (-not $adb) { Write-Host "ERROR: adb.exe not found" -ForegroundColor Red; exit 1 }

# ---------------- device check ----------------
$devList = (& $adb devices 2>&1 | Out-String)
if ($devList -notmatch [regex]::Escape($Device) + '\s+device') {
    Write-Host "ERROR: device not connected ($Device)" -ForegroundColor Red
    exit 1
}

if (-not $OutFile) {
    $stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
    $OutFile = Join-Path $env:TEMP "dash_render_$stamp.png"
}

Write-Host ""
Write-Host "=== dash render dump ===" -ForegroundColor Cyan
Write-Host "  device : $Device"
Write-Host "  output : $OutFile"

# ---------------- capture ----------------
& $adb -s $Device shell "logcat -c" 2>&1 | Out-Null
& $adb -s $Device shell "am force-stop $Pkg; sleep 1; am start -n $Pkg/$Activity" 2>&1 | Out-Null
Write-Host "  waiting $WaitSec s ..." -NoNewline
Start-Sleep -Seconds $WaitSec

# 关键：必须 -v long。默认格式按 ~4KB 截断行，
# 3000 字符的 base64 行会被切断，导致拼接长度不符、PNG 无法解码。
$logText = (& $adb -s $Device shell "logcat -d -v long -s ${Tag}:*" 2>&1 | Out-String)

if ($logText -notmatch 'PNG_B64_BEGIN') {
    Write-Host ""
    Write-Host "ERROR: no PNG_B64_BEGIN in log." -ForegroundColor Red
    Write-Host "  检查：Activity 是否真的启动了 / TAG 是否为 $Tag" -ForegroundColor Yellow
    exit 1
}

# 只取**最后一份** dump：缓冲区可能残留多条 PNG_B64_BEGIN..END 记录，
# 全量拼接会让长度翻倍（实测 798876 = 399952 x 2）。
$blocks = [regex]::Matches($logText, 'PNG_B64_BEGIN (\d+)x(\d+) len=(\d+)(.*?)PNG_B64_END', 'Singleline')
if ($blocks.Count -eq 0) {
    Write-Host "ERROR: no complete PNG_B64_BEGIN..END block" -ForegroundColor Red
    exit 1
}
if ($blocks.Count -gt 1) {
    Write-Host ("  note   : {0} blocks in buffer, using the last one" -f $blocks.Count) -ForegroundColor Yellow
}
$blk = $blocks[$blocks.Count - 1]
$declared = [int]$blk.Groups[3].Value
$b64 = ([regex]::Matches($blk.Groups[4].Value, 'PNG_B64 ([A-Za-z0-9+/=]+)') | ForEach-Object { $_.Groups[1].Value }) -join ''
$w = $blk.Groups[1].Value
$h = $blk.Groups[2].Value

Write-Host ""
Write-Host ("  size   : {0} x {1}" -f $w, $h)
Write-Host ("  base64 : {0} / declared {1}" -f $b64.Length, $declared)

if ($b64.Length -ne $declared) {
    Write-Host "  WARN: length mismatch -> PNG may be corrupt (logcat truncation)" -ForegroundColor Yellow
}

try {
    $bytes = [Convert]::FromBase64String($b64)
    $dir = Split-Path $OutFile -Parent
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [IO.File]::WriteAllBytes($OutFile, $bytes)
    Write-Host ("  PNG    : {0} bytes" -f (Get-Item $OutFile).Length) -ForegroundColor Green
} catch {
    Write-Host "ERROR: base64 decode failed: $_" -ForegroundColor Red
    exit 1
}

# ---------------- restore ----------------
& $adb -s $Device shell "am force-stop $Pkg" 2>&1 | Out-Null
Write-Host "  done." -ForegroundColor Green
