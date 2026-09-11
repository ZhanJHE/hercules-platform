@echo off
rem ============================================================
rem  Hercules one-click launcher (double-click friendly shim).
rem  Logic lives in start-hercules.ps1 (UTF-8 BOM, Chinese output).
rem ============================================================
rem PowerShell via absolute path: never rely on PATH (some environments lack it;
rem mvnw.cmd fails the same way). Keep every line ASCII: this file has no BOM and
rem cmd.exe decodes it with the console code page (GBK on zh-CN Windows).
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-hercules.ps1"
set EC=%ERRORLEVEL%
if "%EC%"=="0" (timeout /t 8 >nul 2>&1) else (echo. & echo Launch failed, exit code: %EC% & pause)
exit /b %EC%
