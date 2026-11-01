@echo off
rem build-dcapd2.bat — 编译 dcapd2（和 build-dcapd.ps1 同一套头文件/库，源码换成 dcapd2.c）
rem 用法: build-dcapd2.bat
setlocal enabledelayedexpansion

set NDK=C:\Users\94790\AppData\Local\Android\Sdk\ndk\26.1.10909125
set CLANG=%NDK%\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android30-clang++.cmd
set AOSP=F:\Documents\Workbuddy\2026-10-22-19-30-22\aosp11
set FW=%AOSP%\aosp_platform_frameworks_native-android-platform-11.0.0_r16
set SC=%AOSP%\aosp_platform_system_core-android-platform-11.0.0_r16
set HERE=%~dp0
if "%HERE:~-1%"=="\" set HERE=%HERE:~0,-1%

if exist "%HERE%\dcapd2" del /q "%HERE%\dcapd2"

call "%CLANG%" -x c++ -target aarch64-linux-android30 -O2 -std=c++17 -Wall -fPIE -pie -fno-rtti ^
  -I "%HERE%\stubs" ^
  -I "%FW%\libs\gui\include" -I "%FW%\libs\ui\include" -I "%FW%\libs\nativebase\include" ^
  -I "%FW%\libs\nativewindow\include" -I "%FW%\libs\binder\include" -I "%FW%\libs\math\include" ^
  -I "%FW%\include" ^
  -I "%SC%\libutils\include" -I "%SC%\libcutils\include" -I "%SC%\liblog\include" ^
  -I "%SC%\libsystem\include" -I "%SC%\include" -I "%SC%\base\include" ^
  -I "%HERE%\..\dashcast_proto" ^
  "%HERE%\dcapd2.c" -o "%HERE%\dcapd2" ^
  -L "%HERE%\carlibs" -lgui -lbinder -lui -lutils -lcutils -lnativewindow -llog -landroid -lc++ ^
  -Wl,--allow-shlib-undefined -Wl,-rpath,/system/lib64

if exist "%HERE%\dcapd2" (
  for %%A in ("%HERE%\dcapd2") do echo BUILD_OK %%~zA bytes
) else (
  echo BUILD_FAIL
  exit /b 1
)
