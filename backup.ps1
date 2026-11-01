# 自动备份脚本
# 用法: .\backup.ps1
# 每次运行会在 _backup\<时间戳>\ 下存一份关键源码快照

$ErrorBank = "Stop"
$Root = $PSScriptRoot
$Stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$Dst = Join-Path $Root "_backup\$Stamp"

New-Item -ItemType Directory -Path $Dst -Force | Out-Null

$Patterns = @(
    "src\ap\app\src\main\java\com\hermes\dashcast\*.java",
    "src\ap\app\src\main\AndroidManifest.xml",
    "src\ap\app\src\main\res\values\*.xml",
    "src\ap-native\*.c",
    "src\ap-native\*.h",
    "src\cp\*.c",
    "src\cp\*.cpp",
    "src\dashcast_proto\*.h",
    "docs\*.md",
    "artifacts\MD5SUMS.txt"
)

$count = 0
foreach ($p in $Patterns) {
    $full = Join-Path $Root $p
    if (Test-Path $full) {
        Copy-Item $full $Dst -Force -ErrorAction SilentlyContinue
        $count += (Get-ChildItem $full -ErrorAction SilentlyContinue | Measure-Object).Count
    }
}

Write-Host "备份完成: $count 个文件 -> $Dst" -ForegroundColor Green

# 只保留最近 10 份，避免无限增长
$all = Get-ChildItem (Join-Path $Root "_backup") -Directory | Sort-Object Name -Descending
if ($all.Count -gt 10) {
    $all | Select-Object -Skip 10 | ForEach-Object {
        Remove-Item $_.FullName -Recurse -Force
        Write-Host "已清理旧备份: $($_.Name)" -ForegroundColor DarkGray
    }
}
