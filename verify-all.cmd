@echo off
rem ============================================================
rem  AI Teaching KB - one-shot verification (ASCII shim)
rem
rem  Delegates to tools\verify-all.ps1 which builds if needed,
rem  starts the app, runs the core-chain checks, stops the app
rem  and prints PASS/FAIL. Exit code 0 = all checks passed.
rem
rem  NOTE: this .cmd is pure ASCII on purpose. cmd.exe parses .cmd
rem  files with the OEM codepage (GBK here); UTF-8 Chinese text in
rem  this file would be mis-parsed as commands. All Chinese output
rem  is produced by the UTF-8 PowerShell script instead.
rem
rem  Usage: verify-all.cmd           (default port 18080)
rem         verify-all.cmd 9090      (custom port)
rem ============================================================
setlocal
cd /d "%~dp0"
set "RC=1"
set "PORT=%~1"
if "%PORT%"=="" set "PORT=18080"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\verify-all.ps1" -Port %PORT% -Root "%~dp0."
set "RC=%errorlevel%"
if not defined RC set "RC=1"

endlocal & exit /b %RC%