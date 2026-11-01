# build-ap.ps1 — 一条命令出 APK（native 库 + Java 一起打进去）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File build-ap.ps1            # 只编译
#   powershell -ExecutionPolicy Bypass -File build-ap.ps1 -Install   # 编译 + adb install -r
#   powershell -ExecutionPolicy Bypass -File build-ap.ps1 -Restart   # 编译 + 安装 + 拉起 App
#
# 为什么 native 不走 gradle 的 externalNativeBuild：
#   libdcap.so 只依赖 jni.h + liblog，不用 CMake 也不碰 AOSP 那些头文件。
#   直接 clang 一条命令编好丢进 jniLibs，gradle 只负责打包，构建链少两层，出错点也少。

param(
    [switch]$Install,
    [switch]$Restart
)

$ErrorActionPreference = "Stop"

$NDK     = "C:\Users\94790\AppData\Local\Android\Sdk\ndk\26.1.10909125"
$CLANG   = "$NDK\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android30-clang.cmd"
$SDK     = "C:\Users\94790\AppData\Local\Android\Sdk"
$ADB     = "$SDK\platform-tools\adb.exe"

$HERE    = Split-Path -Parent $MyInvocation.MyCommand.Path    # ...\src\ap
$JNI     = "$HERE\app\src\main\jni"
# 协议头唯一真身处：...\src\dashcast_proto\（不是旧的 ...\src\）
$PROTO   = "$HERE\..\dashcast_proto"                 # dashcast_proto.h / lz4mini.h
$OUTDIR  = "$HERE\app\src\main\jniLibs\arm64-v8a"
$SO      = "$OUTDIR\libdcap.so"
$APK     = "$HERE\app\build\outputs\apk\debug\app-debug.apk"

function Step($msg) { Write-Host ""; Write-Host "== $msg ==" -ForegroundColor Cyan }

# ---------------------------------------------------------------- 1. native
Step "编译 libdcap.so"

if (-not (Test-Path $CLANG)) { throw "找不到 NDK clang: $CLANG" }
if (-not (Test-Path "$PROTO\dashcast_proto.h")) { throw "找不到协议头: $PROTO\dashcast_proto.h" }

New-Item -ItemType Directory -Force -Path $OUTDIR | Out-Null

$args1 = New-Object System.Collections.ArrayList
[void]$args1.Add("-shared"); [void]$args1.Add("-fPIC")
[void]$args1.Add("-O2");    [void]$args1.Add("-std=c11")
[void]$args1.Add("-Wall");  [void]$args1.Add("-Wextra")
[void]$args1.Add("-Wno-unused-parameter")
[void]$args1.Add("-target"); [void]$args1.Add("aarch64-linux-android30")
[void]$args1.Add("-I"); [void]$args1.Add($PROTO)
[void]$args1.Add("-o"); [void]$args1.Add($SO)
[void]$args1.Add("$JNI\dcap_jni.c")
[void]$args1.Add("-llog")

$buildOut = & $CLANG @args1 2>&1 | Out-String
if ($buildOut.Trim().Length -gt 0) { Write-Host $buildOut.Trim() }

if (-not (Test-Path $SO)) { throw "libdcap.so 没生成" }
$soSize = [math]::Round((Get-Item $SO).Length / 1KB, 1)
Write-Host "  libdcap.so  $soSize KB" -ForegroundColor Green

# ---------------------------------------------------------------- 2. gradle
Step "gradle assembleDebug"

$env:JAVA_HOME = "E:\sdk\jbr"
$GRADLE = (Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists" -Recurse -Filter "gradle.bat" -ErrorAction SilentlyContinue |
           Where-Object { $_.FullName -like "*gradle-8.7*" } | Select-Object -First 1).FullName
if (-not $GRADLE) { throw "找不到 gradle.bat（~\.gradle\wrapper\dists 下没有 gradle-8.7）" }

Push-Location $HERE
try {
    & $GRADLE --offline --console=plain assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "gradle 失败，退出码 $LASTEXITCODE" }
} finally {
    Pop-Location
}

if (-not (Test-Path $APK)) { throw "APK 没生成: $APK" }
Write-Host ("  {0}  {1} KB" -f (Split-Path -Leaf $APK), [math]::Round((Get-Item $APK).Length / 1KB, 1)) -ForegroundColor Green

# ---------------------------------------------------------------- 3. 装
if ($Install -or $Restart) {
    Step "adb install"
    & $ADB install -r $APK
    if ($LASTEXITCODE -ne 0) { throw "adb install 失败" }
}

if ($Restart) {
    Step "拉起 App"
    & $ADB shell am force-stop com.hermes.dashcast
    & $ADB shell am start -n com.hermes.dashcast/.MainActivity
    Write-Host "  日志: adb logcat -s DashcastSender DcapJni DashcastMain" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "done." -ForegroundColor Green
