# push-dashcmd.ps1  — 首次安装并启动 CP 端 dashcmd 命令守护（8445 明文通道）
# 用法：先确保 adb forward 已建（127.0.0.1:2222 -> CP:22），然后运行本脚本
#   plink 连 root@127.0.0.1:2222（空密码），推 dashcmd 到 /run/dashcmd 并启动

$ErrorActionPreference = "Stop"

$plink = "C:\Program Files\PuTTY\plink.exe"
$b64   = "F:\Documents\Workbuddy\2026-10-22-19-30-22\dashcast\cp\dashcmd.b64"
$host_ = "127.0.0.1"
$port  = 2222
$user  = "root"
$remote = "/run/dashcmd"

Write-Host "[1/4] Reading base64 file..." -ForegroundColor Cyan
$raw = Get-Content -LiteralPath $b64 -Raw

# strip any header/footer lines
$lines = $raw -split "`r?`n"
$body = ($lines | Where-Object { $_.Trim() -ne '' }) -join "`n"
Write-Host ("[2/4] base64 payload length: " + $body.Length) -ForegroundColor Cyan

# remote: decode stdin -> /run/dashcmd, chmod, print result
$remoteCmd = "base64 -d > $remote; chmod +x $remote; echo '--- RESULT ---'; ls -la $remote; echo '--- MD5 ---'; md5sum $remote 2>/dev/null || true"

Write-Host "[3/4] plink push dashcmd..." -ForegroundColor Cyan

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

$stdout = $p.StandardOutput.ReadToEnd()
$stderr = $p.StandardError.ReadToEnd()
$p.WaitForExit()

Write-Host "[4/4] CP output:" -ForegroundColor Cyan
Write-Host $stdout
if ($stderr) { Write-Host ("STDERR: " + $stderr) -ForegroundColor Yellow }

if ($stdout -notmatch 'dashcmd') {
    Write-Host ""
    Write-Host "[FAIL] dashcmd push failed, check output above" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "[5/5] Starting dashcmd (nohup, port 8445)..." -ForegroundColor Cyan
$startCmd = "killall dashcmd 2>/dev/null; nohup /run/dashcmd >/dev/null 2>&1 & echo STARTED"

$psi2 = New-Object System.Diagnostics.ProcessStartInfo
$psi2.FileName = $plink
$psi2.Arguments = "-batch -pw `"`" -P $port $user@$host_ `"$startCmd`""
$psi2.RedirectStandardInput  = $true
$psi2.RedirectStandardOutput = $true
$psi2.RedirectStandardError  = $true
$psi2.UseShellExecute = $false
$psi2.CreateNoWindow = $true

$p2 = [System.Diagnostics.Process]::Start($psi2)
$p2.StandardInput.Close()
$out2 = $p2.StandardOutput.ReadToEnd()
$p2.WaitForExit()

Write-Host $out2
Write-Host ""
Write-Host "[OK] dashcmd installed & started on CP port 8445" -ForegroundColor Green
Write-Host "     verify: plink root@127.0.0.1:2222 'echo PING | nc 127.0.0.1 8445'" -ForegroundColor DarkGray
