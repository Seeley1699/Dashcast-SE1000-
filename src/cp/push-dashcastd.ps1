# push-dashcastd.ps1  (v4, ASCII-only, plink auto-login)
# Push dashcastd to CP: read .b64 -> strip header/footer -> plink stdin -> CP decode + verify
#
# 用法（先 adb forward tcp:2222 tcp:22，把车机 127.0.0.1:2222 指到 CP:22）：
#   adb forward tcp:2222 tcp:22
#   powershell -ExecutionPolicy Bypass -File push-dashcastd.ps1

$ErrorActionPreference = "Stop"

$plink = "C:\Program Files\PuTTY\plink.exe"
# 相对脚本自身定位：...\src\cp\cp\assets\.. 不对 —— 资产在 ...\src\ap\app\src\main\assets\dashcastd.b64
$HERE  = Split-Path -Parent $MyInvocation.MyCommand.Path               # ...\src\cp
$b64   = "$HERE\..\ap\app\src\main\assets\dashcastd.b64"              # APK 资产同名文件
$host_ = "127.0.0.1"
$port  = 2222
$user  = "root"
$remote= "/run/dashcastd"     # 主部署路径是 /run（tmpfs），不是旧的 /tmp

Write-Host "[1/4] Reading base64 file..." -ForegroundColor Cyan
if (-not (Test-Path -LiteralPath $b64)) {
    # 退回老位置（cp/dashcastd.b64，与本资产同源）
    $alt = "$HERE\dashcastd.b64"
    if (Test-Path -LiteralPath $alt) { $b64 = $alt }
    else { throw "找不到 dashcastd.b64（找过: $b64 / $alt）" }
}
$raw = Get-Content -LiteralPath $b64 -Raw

# strip certutil BEGIN/END lines
$lines = $raw -split "`r?`n"
$body = ($lines | Where-Object { $_ -notmatch 'BEGIN CERTIFICATE' -and $_ -notmatch 'END CERTIFICATE' -and $_.Trim() -ne '' }) -join "`n"
Write-Host ("[2/4] base64 payload length: " + $body.Length) -ForegroundColor Cyan

# remote command: decode stdin into file, chmod, then print size + md5
$remoteCmd = "mkdir -p /run; base64 -d > $remote; chmod 755 $remote; echo '--- RESULT ---'; ls -la $remote; echo '--- MD5 ---'; md5sum $remote 2>/dev/null || true"

Write-Host "[3/4] plink login and push..." -ForegroundColor Cyan

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $plink
$psi.Arguments = "-batch -pw `"`" -P $port $user@$host_ `"$remoteCmd`""
$psi.RedirectStandardInput  = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError  = $true
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true

$p = [System.Diagnostics.Process]::Start($psi)
$p.StandardInput.Write($body)
$p.StandardInput.Close()

$stdout  = $p.StandardOutput.ReadToEnd()
$stderr  = $p.StandardError.ReadToEnd()
$hadExit = $p.WaitForExit(20000)

Write-Host "[4/4] CP output:" -ForegroundColor Cyan
Write-Host $stdout
if ($stderr) { Write-Host ("STDERR: " + $stderr) -ForegroundColor Yellow }

if ($stdout -match '--- RESULT ---' -and $stdout -match '--- MD5 ---') {
    Write-Host ""
    Write-Host "[OK] dashcastd pushed to CP $remote" -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "[FAIL] check output above（真实二进制 533168 字节，见顶部说明）" -ForegroundColor Red
}