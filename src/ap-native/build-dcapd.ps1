# build-dcapd.ps1 — 编译车机侧 native 抓帧进程 dcapd
# 用法: powershell -ExecutionPolicy Bypass -File build-dcapd.ps1
$ErrorActionPreference = "Continue"

$NDK = "C:\Users\94790\AppData\Local\Android\Sdk\ndk\26.1.10909125"
$CLANGPP = "$NDK\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android30-clang++.cmd"
$AOSP11 = "F:\Documents\Workbuddy\2026-10-22-19-30-22\aosp11"
$FW = "$AOSP11\aosp_platform_frameworks_native-android-platform-11.0.0_r16"
$SC = "$AOSP11\aosp_platform_system_core-android-platform-11.0.0_r16"
$HERE = Split-Path -Parent $MyInvocation.MyCommand.Path
$STUBS = "$HERE\stubs"
$CARLIBS = "$HERE\carlibs"

$cliArgs = New-Object System.Collections.ArrayList
[void]$cliArgs.Add("-target"); [void]$cliArgs.Add("aarch64-linux-android30")
[void]$cliArgs.Add("-O2"); [void]$cliArgs.Add("-std=c++17"); [void]$cliArgs.Add("-Wall")
[void]$cliArgs.Add("-fPIE"); [void]$cliArgs.Add("-pie")
[void]$cliArgs.Add("-fno-rtti")

$inc = @($STUBS, "$FW\libs\gui\include", "$FW\libs\ui\include", "$FW\libs\nativebase\include", "$FW\libs\nativewindow\include", "$FW\libs\binder\include", "$FW\libs\math\include", "$FW\include", "$SC\libutils\include", "$SC\libcutils\include", "$SC\liblog\include", "$SC\libsystem\include", "$SC\include", "$SC\base\include")
foreach ($p in $inc) { [void]$cliArgs.Add("-I"); [void]$cliArgs.Add($p) }

$linkArgs = New-Object System.Collections.ArrayList
[void]$linkArgs.Add("-L"); [void]$linkArgs.Add($CARLIBS)
foreach ($l in @("gui","binder","ui","utils","cutils","nativewindow","log","android","c++")) {
    [void]$linkArgs.Add("-l$l")
}
[void]$linkArgs.Add("-Wl,--allow-shlib-undefined")
[void]$linkArgs.Add("-Wl,-rpath,/system/lib64")

$out = & $CLANGPP @cliArgs "$HERE\dcapd.c" -o "$HERE\dcapd" @linkArgs 2>&1 | Out-String
$out | Out-File "$env:TEMP\dc_build_result.txt" -Encoding utf8

if (Test-Path "$HERE\dcapd") {
    $sz = (Get-Item "$HERE\dcapd").Length
    Write-Output "BUILD_OK $([math]::Round($sz/1KB,1)) KB"
} else {
    Write-Output "BUILD_FAIL"
    Write-Output $out
}
