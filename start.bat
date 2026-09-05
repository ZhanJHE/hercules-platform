@echo off
rem ============================================================
rem  Hercules one-click launcher (double-click friendly shim).
rem  Logic lives in start-hercules.ps1 (UTF-8 BOM, Chinese output).
rem ============================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-hercules.ps1"
set EC=%ERRORLEVEL%
if "%EC%"=="0" (timeout /t 8 >nul 2>&1) else (echo. & echo ???: %EC% & pause)
exit /b %EC%
