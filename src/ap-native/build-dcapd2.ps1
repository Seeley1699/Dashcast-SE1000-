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

$out = & $CLANG @a 2>&1 | Out-String

if (Test-Path "$HERE\dcapd2") {
    $sz = (Get-Item "$HERE\dcapd2").Length
    $msg = "BUILD_OK $([math]::Round($sz/1KB,1)) KB"
} else {
    $msg = "BUILD_FAIL`r`n$out"
}
Write-Output $msg
$msg | Out-File "$HERE\_build_dcapd2.log" -Encoding utf8
