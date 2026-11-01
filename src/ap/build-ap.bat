@echo off
REM build-ap.bat — 双击即用（绕过 PowerShell 执行策略限制）
REM   build-ap.bat              只编译
REM   build-ap.bat -Install     编译 + adb install -r
REM   build-ap.bat -Restart     编译 + 安装 + 拉起 App
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-ap.ps1" %*
if errorlevel 1 pause
