@echo off
REM build-dcapd.bat — 用 NDK 26 交叉编译车机侧 dcapd (aarch64-android30)
setlocal

set "NDK=C:\Users\94790\AppData\Local\Android\Sdk\ndk\26.1.10909125"
set "CLANG=%NDK%\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android30-clang.cmd"

set "HERE=%~dp0"
set "AOSP11=F:\Documents\Workbuddy\2026-10-22-19-30-22\aosp11"
set "FW_NATIVE=%AOSP11%\aosp_platform_frameworks_native-android-platform-11.0.0_r16"
set "SYS_CORE=%AOSP11%\aosp_platform_system_core-android-platform-11.0.0_r16"

echo [build] 编译 %HERE%dcapd.c

"%CLANG%" -target aarch64-linux-android30 -O2 -std=c++17 -Wall ^
  -Wl,--allow-shlib-undefined ^
  -I "%FW_NATIVE%\libs\gui\include" ^
  -I "%FW_NATIVE%\libs\ui\include" ^
  -I "%FW_NATIVE%\libs\nativebase\include" ^
  -I "%FW_NATIVE%\libs\nativewindow\include" ^
  -I "%FW_NATIVE%\include" ^
  -I "%SYS_CORE%\libutils\include" ^
  -I "%SYS_CORE%\libcutils\include" ^
  -I "%SYS_CORE%\liblog\include" ^
  -I "%SYS_CORE%\libsystem\include" ^
  -I "%SYS_CORE%\include" ^
  "%HERE%dcapd.c" -o "%HERE%dcapd" ^
  -lgui -lbinder -lui -lutils -lcutils -llog -lnativewindow -lGLESv2 -lEGL -landroid -lc++ -shared-libgcc

if %ERRORLEVEL% NEQ 0 (
  echo [build] 失败 ^(exit %ERRORLEVEL%^)
  exit /b 1
)

echo [build] 成功: %HERE%dcapd
for %%A in ("%HERE%dcapd") do echo [build] 产物大小: %%~zA 字节
exit /b 0
