@echo off
setlocal
set ROOT=%~dp0
if not exist "%ROOT%.tools\gradle-8.11.1\bin\gradle.bat" (
  echo Run powershell -ExecutionPolicy Bypass -File scripts\bootstrap.ps1 -InstallSdk first.
  exit /b 1
)
for /d %%J in ("%ROOT%.tools\jdk\*") do set "JAVA_HOME=%%~fJ"
set "GRADLE_USER_HOME=%ROOT%.tools\gradle-cache"
call "%ROOT%.tools\gradle-8.11.1\bin\gradle.bat" -p "%ROOT%." %*
exit /b %ERRORLEVEL%
