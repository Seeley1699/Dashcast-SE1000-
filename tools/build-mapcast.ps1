# build-mapcast.ps1 —— 打包 mapcast.js（供 Frida 注入）
#
# 拼接顺序（重要，函数必须先定义后使用）：
#   1. java-bridge.js      Frida 的 Java 桥接
#   2. canvas2d_adapter.js Canvas 2D → Android Canvas 翻译层
#   3. dashboard.js        你的仪表盘（原样，只改了末尾的自动挂载）
#   4. dash_bridge.js      把 dashboard.js 接到 Android 环境（DOM 桩 + 每帧调用）
#   5. vehicle.js          车辆数据（五路并联）
#   6. media.js            媒体/歌词
#   7. config.js           布局+主题+热重载
#   8. map_to_dash_prod.js 主流程

param(
    [string]$Out = 'E:\Program Files\DSH\dashcast\src\ap\app\src\main\assets\mapcast.js',
    [switch]$Verbose
)

$ErrorActionPreference = 'Stop'
$Root = 'E:\Program Files\DSH\dashcast'
$Bridge = 'F:\Desktop\车机\世界\银河视界_源码_全量_v2\assets_decrypted\runtime\java-bridge.js'

$parts = @(
    @{ Name = 'java-bridge';          Path = $Bridge },
    @{ Name = 'canvas2d_adapter.js';  Path = "$Root\_frida\canvas2d_adapter.js" },
    @{ Name = 'dashboard.js';         Path = "$Root\_frida\dashboard.js" },
    @{ Name = 'dash_bridge.js';       Path = "$Root\_frida\dash_bridge.js" },
    @{ Name = 'vehicle.js';           Path = "$Root\_frida\vehicle.js" },
    @{ Name = 'media.js';             Path = "$Root\_frida\media.js" },
    @{ Name = 'config.js';            Path = "$Root\_frida\config.js" },
    @{ Name = 'map_to_dash_prod.js';  Path = "$Root\_frida\map_to_dash_prod.js" }
)

$sb = New-Object System.Text.StringBuilder
$totalLines = 0

foreach ($p in $parts) {
    if (-not (Test-Path $p.Path)) {
        Write-Host "ERROR: 找不到 $($p.Path)" -ForegroundColor Red
        exit 1
    }
    $content = [IO.File]::ReadAllText($p.Path, [Text.Encoding]::UTF8)
    $lines = ($content -split "`n").Count
    $totalLines += $lines
    if ($Verbose) {
        Write-Host ("  {0,-24} {1,8:N0} B  {2,6} 行" -f $p.Name, $content.Length, $lines)
    }
    [void]$sb.Append("`n/* ===== $($p.Name) ===== */`n")
    [void]$sb.Append($content)
}

$combined = $sb.ToString()
[IO.File]::WriteAllText($Out, $combined, (New-Object System.Text.UTF8Encoding($false)))

$size = (Get-Item $Out).Length
$md5 = (Get-FileHash $Out -Algorithm MD5).Hash.ToLower()

$checks = @(
    'function Canvas2D',
    'class HUD',
    'function dashInit',
    'function dashFrame',
    'function vehInit',
    'function medInit',
    'function cfgxInit',
    'function d2_connect'
)

$missing = @()
foreach ($c in $checks) {
    if ($combined -notmatch [regex]::Escape($c)) { $missing += $c }
}

Write-Host ""
Write-Host "打包完成: $Out"
Write-Host ("  大小: {0:N1} KB   {1:N0} 行" -f ($size / 1KB), $totalLines)
Write-Host "  MD5:  $md5"
Write-Host "  段数: $($parts.Count)"

if ($missing.Count -gt 0) {
    Write-Host "  警告: 缺少 $($missing -join ', ')" -ForegroundColor Yellow
} else {
    Write-Host "  检查: 所有关键函数已包含" -ForegroundColor Green
}