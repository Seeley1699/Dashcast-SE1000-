# fetch-injector.ps1 — 从原车 APK 提取 Frida 注入器,生成 APK 构建所需的 assets
#
# 背景:
#   地图投屏靠一个剥离过的 Frida injector(hudcast-inject,ARM64 ELF,56MB)
#   在车机上注入 com.geely.map 进程,执行 assets/mapcast.js.
#   这个 injector 来自原车预装的"银河世界.apk",不随本项目分发(24MB gzip 太大).
#
# 这个脚本做三件事:
#   ① 从源 APK 里抽出 assets/runtime/hudcast-inject.xz
#   ② 用 7-Zip 解压成裸 ELF(* Python 的 lzma 不支持 ARM64 BCJ 滤波器,
#      解出来只有 25MB、运行时段错误 —— 必须用 7-Zip)
#   ③ gzip 压回 24MB,落到 src/ap/app/src/main/assets/hudcast-inject.bin
#
# 用法:
#   powershell -ExecutionPolicy Bypass -File tools\fetch-injector.ps1 `
#       -SourceApk 'F:\Desktop\车机\银河世界.apk'
#
# 前置:
#   7-Zip(默认找 E:\Program Files\7-Zip\7z.exe,可用 -SevenZip 指定)

param(
    [Parameter(Mandatory = $true)]
    [string]$SourceApk,

    [string]$SevenZip = 'E:\Program Files\7-Zip\7z.exe',

    [string]$OutBin = ''
)

$ErrorActionPreference = 'Stop'

$HERE = Split-Path -Parent $MyInvocation.MyCommand.Path          # ...\dashcast\tools
$ROOT = Split-Path -Parent $HERE                                  # ...\dashcast
if (-not $OutBin) {
    $OutBin = Join-Path $ROOT 'src\ap\app\src\main\assets\hudcast-inject.bin'
}

function Say($m) { Write-Host $m }

# -- 前置检查 ──────────────────────────────────────────────
if (-not (Test-Path $SourceApk)) { Say "ERROR: 源 APK 不存在: $SourceApk"; exit 1 }
if (-not (Test-Path $SevenZip))  { Say "ERROR: 找不到 7-Zip: $SevenZip"; Say "  用 -SevenZip 指定路径"; exit 1 }

$apkSize = (Get-Item $SourceApk).Length
Say ("源 APK : {0}  ({1:N1} MB)" -f $SourceApk, ($apkSize / 1MB))
Say ("目标   : {0}" -f $OutBin)

$tmp = Join-Path $env:TEMP ("fetch_injector_" + (Get-Date -Format 'yyyyMMdd_HHmmss'))
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
Say ("临时区 : {0}" -f $tmp)

try {
    # -- ① 从 APK 里抽出 .xz ────────────────────────────────
    Say ""
    Say "[1/4] 从 APK 抽取 assets/runtime/hudcast-inject.xz ..."
    $xzInApk = 'assets/runtime/hudcast-inject.xz'
    $xz = Join-Path $tmp 'hudcast-inject.xz'

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($SourceApk)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq $xzInApk }
        if (-not $entry) {
            Say "ERROR: APK 里没有 $xzInApk"
            Say "  实际包含的 runtime 条目:"
            $zip.Entries | Where-Object { $_.FullName -like 'assets/runtime/*' } |
                Select-Object -First 10 | ForEach-Object { Say ("    " + $_.FullName) }
            exit 1
        }
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $xz, $true)
        Say ("  -> {0:N1} MB" -f ((Get-Item $xz).Length / 1MB))
    } finally { $zip.Dispose() }

    # -- ② 7-Zip 解压(必须用 7z,Python lzma 缺 ARM64 BCJ)──
    Say ""
    Say "[2/4] 7-Zip 解压(ARM64 BCJ + LZMA2,Python lzma 不支持)..."
    $elf = Join-Path $tmp 'hudcast-inject'
    # cmd 重定向:PowerShell 的 > 会写出 UTF-16,ELF 直接损坏
    $q7 = '"' + $SevenZip + '"'
    $qxz = '"' + $xz + '"'
    $qout = '"' + $elf + '"'
    $cmdLine = "$q7 x -so $qxz > $qout"
    cmd /c $cmdLine
    if (-not (Test-Path $elf)) { Say "ERROR: 7-Zip 解压失败"; exit 1 }
    $elfLen = (Get-Item $elf).Length
    Say ("  -> {0:N0} bytes ({1:N1} MB)" -f $elfLen, ($elfLen / 1MB))

    # 校验 ELF 头(7f 45 4c 46)
    $hdr = [IO.File]::ReadAllBytes($elf)[0..3]
    if (($hdr -join ',') -ne '127,69,76,70') {
        Say ("ERROR: 解出来的不是 ELF(前 4 字节 = " + ($hdr -join ',') + ")")
        Say "  典型原因:用了 Python lzma 或 PowerShell > 重定向(UTF-16 损坏)"
        exit 1
    }
    Say "  -> ELF 头校验通过"

    # -- ③ gzip ─────────────────────────────────────────────
    Say ""
    Say "[3/4] gzip 压缩 ..."
    $gz = Join-Path $tmp 'hudcast-inject.bin'
    $fs = [IO.File]::OpenRead($elf)
    $out = [IO.File]::Create($gz)
    # Optimal 等价 gzip -6,实测 56MB -> 24MB,压缩耗时约 2 秒
    # New-Object 的括号形式在单行里会解析出错,用 ArgumentList 形式
    $gzs = New-Object -TypeName System.IO.Compression.GZipStream -ArgumentList `
        $out, ([System.IO.Compression.CompressionLevel]::Optimal)
    $buf = New-Object byte[] 65536
    while (($r = $fs.Read($buf, 0, 65536)) -gt 0) { $gzs.Write($buf, 0, $r) }
    $gzs.Dispose(); $out.Dispose(); $fs.Dispose()
    Say ("  -> {0:N1} MB" -f ((Get-Item $gz).Length / 1MB))

    # -- ④ 落盘 + md5 ───────────────────────────────────────
    Say ""
    Say "[4/4] 落到 assets ..."
    $dir = Split-Path -Parent $OutBin
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    Copy-Item $gz $OutBin -Force
    $md5 = (Get-FileHash $OutBin -Algorithm MD5).Hash.ToLower()
    Say ("  -> {0}" -f $OutBin)
    Say ("  -> MD5 {0}" -f $md5)
    Say ""
    Say "完成.现在可以构建 APK:"
    Say "  powershell -ExecutionPolicy Bypass -File src\ap\build-ap.ps1 -Install"
}
finally {
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue }
}
