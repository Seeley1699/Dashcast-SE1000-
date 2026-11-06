# build-dcapd2.ps1 — 编译「单应用镜像」抓帧守护进程 dcapd2
#   和 build-dcapd.ps1 同一套头文件 / 链接库，只是源码换成 dcapd2.c
# 用法: powershell -ExecutionPolicy Bypass -File build-dcapd2.ps1
$ErrorActionPreference = "Continue"

$NDK   = "C:\Users\94790\AppData\Local\Android\Sdk\ndk\26.1.10909125"
$CLANG = "$NDK\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android30-clang++.cmd"
$AOSP11 = "F:\Documents\Workbuddy\2026-10-22-19-30-22\aosp11"
$FW = "$AOSP11\aosp_platform_frameworks_native-android-platform-11.0.0_r16"
$SC = "$AOSP11\aosp_platform_system_core-android-platform-11.0.0_r16"
# 脚本自身所在目录（...\src\ap-native），不再硬编码 F: 盘 —— 包可整体搬走
$HERE = Split-Path -Parent $MyInvocation.MyCommand.Path

$a = New-Object System.Collections.ArrayList
[void]$a.Add("-x"); [void]$a.Add("c++")          # 源码是 .c，但必须按 C++ 编（libgui 头文件）
[void]$a.Add("-target"); [void]$a.Add("aarch64-linux-android30")
[void]$a.Add("-O2"); [void]$a.Add("-std=c++17"); [void]$a.Add("-Wall")
[void]$a.Add("-fPIE"); [void]$a.Add("-pie"); [void]$a.Add("-fno-rtti")

$inc = @(
  "$HERE\ndkmedia",          # NDK media/* 头文件的本地副本（原路径被 AOSP 目录挡住）
  "$HERE\stubs",
  "$FW\libs\gui\include", "$FW\libs\ui\include", "$FW\libs\nativebase\include",
  "$FW\libs\nativewindow\include", "$FW\libs\binder\include", "$FW\libs\math\include",
  "$FW\include",
  "$SC\libutils\include", "$SC\libcutils\include", "$SC\liblog\include",
  "$SC\libsystem\include", "$SC\include", "$SC\base\include",
  "$HERE\..\dashcast_proto"                       # dashcast_proto.h（...\src\dashcast_proto\）
)
foreach ($p in $inc) { [void]$a.Add("-I"); [void]$a.Add($p) }

[void]$a.Add("$HERE\dcapd2.c")
[void]$a.Add("$HERE\h264enc.c")
[void]$a.Add("$HERE\layer_mask.c")
[void]$a.Add("-o"); [void]$a.Add("$HERE\dcapd2")

[void]$a.Add("-L"); [void]$a.Add("$HERE\carlibs")
foreach ($l in @("gui","binder","ui","utils","cutils","nativewindow","log","android","mediandk","c++")) {
  [void]$a.Add("-l$l")
}
[void]$a.Add("-Wl,--allow-shlib-undefined")
[void]$a.Add("-Wl,-rpath,/system/lib64")

# **编译前先删旧产物**（2026-11-05 修「假成功」）
#
# 原来只看 Test-Path：编译失败时旧的 dcapd2 还在目录里，
# 脚本照样报 BUILD_OK，MD5 一点没变 —— 以为改了、实际没改，
# 打包出去的 APK 里还是旧二进制，白白排查半天。
# 现在先删掉，编译完看它在不在，假不了。
$bin = "$HERE\dcapd2"
if (Test-Path $bin) { Remove-Item $bin -Force -ErrorAction SilentlyContinue }

$out = & $CLANG @a 2>&1 | Out-String

if (Test-Path $bin) {
    $sz = (Get-Item $bin).Length
    $md5 = (([Security.Cryptography.MD5]::Create().ComputeHash(
                [IO.File]::ReadAllBytes($bin))) | ForEach-Object { $_.ToString('x2') }) -join ''
    $msg = "BUILD_OK $([math]::Round($sz/1KB,1)) KB  md5=$md5"
} else {
    $msg = "BUILD_FAIL`r`n$out"
}
Write-Output $msg
$msg | Out-File "$HERE\_build_dcapd2.log" -Encoding utf8
if (-not (Test-Path $bin)) { exit 1 }
