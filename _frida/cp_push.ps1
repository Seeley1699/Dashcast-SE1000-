param(
    [string]$LocalBin = 'E:\Program Files\DSH\dashcast\build_out\dashcastd',
    [string]$RemotePath = '/run/dashcastd',
    [int]$ChunkSize = 4000,
    [int]$DelayMs = 700
)

$adb = 'C:\Users\94790\AppData\Local\Android\Sdk\platform-tools\adb.exe'

function CpExec([string]$cmd, [int]$waitMs = 2500) {
    $bb = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($cmd))
    & $adb shell "rm -f /data/user/11/com.hermes.dashcast/files/cpcmd.out" 2>&1 | Out-Null
    $out = & $adb shell "am broadcast -n com.hermes.dashcast/.DiagReceiver -a com.hermes.dashcast.CPCMD --es cmd b64:$bb" 2>&1 | Out-String
    if ($out -match 'result=0') { } elseif ($out -match 'result=') { return "BCAST_FAIL " + $out }
    $res = ''
    for ($n = 0; $n -lt ($waitMs / 100); $n++) {
        Start-Sleep -Milliseconds 100
        $res = & $adb shell "cat /data/user/11/com.hermes.dashcast/files/cpcmd.out 2>/dev/null" | Out-String
        if ($res -match 'output ---') { return $res }
        if ($res -match 'ERR:') { return $res }
    }
    return "TIMEOUT " + $res
}

$bytes = [IO.File]::ReadAllBytes($LocalBin)
$md5 = (Get-FileHash $LocalBin -Algorithm MD5).Hash.ToLower()
$b64 = [Convert]::ToBase64String($bytes)
$total = [Math]::Ceiling($b64.Length / $ChunkSize)
Write-Host ("LOCAL {0}B md5={1} b64={2} chunks={3}" -f $bytes.Length, $md5, $b64.Length, $total)

$b64tmp = $RemotePath + '.b64'
$r = CpExec "rm -f $b64tmp $RemotePath; echo CLEARED" 1500
Write-Host ("CLEAR: " + ($r -match 'CLEARED'))

$fail = 0
for ($i = 0; $i -lt $total; $i++) {
    $start = $i * $ChunkSize
    $len = [Math]::Min($ChunkSize, $b64.Length - $start)
    $piece = $b64.Substring($start, $len)
    if ($i -eq 0) { $op = '>' } else { $op = '>>' }
    $cmd = "printf '%s' '$piece' $op $b64tmp"
    $r = CpExec $cmd 2500
    if (-not ($r -match 'output ---')) {
        $fail++
        $snip = $r.Substring(0, [Math]::Min(70, $r.Length)).Replace("`r","").Replace("`n"," ")
        Write-Host ("  chunk {0}/{1} FAIL: {2}" -f ($i+1), $total, $snip)
    }
    if ((($i + 1) % 10 -eq 0) -or ($i -eq $total - 1)) {
        # 每 10 片校验实际大小
        $sz = CpExec "wc -c < $b64tmp 2>/dev/null || echo 0" 800
        $curSz = ($sz -split "`n" | Where-Object { $_ -match '^\s*\d+' } | Select-Object -First 1)
        $expect = [Math]::Min(($i+1) * $ChunkSize, $b64.Length)
        Write-Host ("  {0}/{1}  remote={2} expect={3} fail={4}" -f ($i+1), $total, $curSz.Trim(), $expect, $fail)
    }
    Start-Sleep -Milliseconds $DelayMs
}

Write-Host "DECODING..."
$r = CpExec "base64 -d $b64tmp > $RemotePath 2>/dev/null; chmod 755 $RemotePath; rm -f $b64tmp; echo DECODED" 4000
Write-Host ("DECODE: " + ($r -match 'DECODED'))

Start-Sleep -Milliseconds 500
$r = CpExec "md5sum $RemotePath 2>/dev/null; echo SEP; ls -la $RemotePath" 1500
Write-Host "=== VERIFY ==="
$r -split "`n" | Where-Object { $_ -notmatch 'ACTION=|CMD=b64' } | ForEach-Object { "  " + $_.Trim() }

if ($r -match [regex]::Escape($md5)) { Write-Host "*** MD5 MATCH ***" } else { Write-Host "*** MD5 MISMATCH ***" }