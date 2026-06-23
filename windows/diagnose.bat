@echo off
setlocal EnableDelayedExpansion

:begin

REM ============================================================================
REM  torchain - Windows Diagnostics and Environment Checker
REM
REM  This script scans the system to check:
REM    1. Administrative privileges & OS version
REM    2. Python installation & GUI support
REM    3. Tor binary & Pluggable Transports
REM    4. Environment variables & PATH settings
REM    5. Network proxy registry keys & Firewall rules
REM    6. Active processes & listening ports
REM ============================================================================

REM --- Colors ---
for /f "delims=" %%a in ('powershell -NoProfile -Command "[char]27"') do set "ESC=%%a"
if not "%ESC:~1%"=="" set "ESC="
set "CYAN=%ESC%[36m"
set "GREEN=%ESC%[32m"
set "YELLOW=%ESC%[33m"
set "RED=%ESC%[31m"
set "MAGENTA=%ESC%[35m"
set "RESET=%ESC%[0m"

echo.
echo !MAGENTA!  ======================================================!RESET!
echo !MAGENTA!   torchain Windows Diagnostics ^& Environment Checker!RESET!
echo !MAGENTA!  ======================================================!RESET!
echo.

set "APPDIR=%ProgramData%\torchain\app"
set "PYDIR=%APPDIR%\python"
set "TORDIR=%APPDIR%\tor"
set "DATADIR=%ProgramData%\torchain\data"

REM ============================================================================
REM  1. System & Privilege Check
REM ============================================================================
echo !CYAN!  [1] Checking System and Privileges...!RESET!

net session >nul 2>&1
if !errorlevel! equ 0 (
    echo   - Privilege level   : !GREEN!Administrator [Elevated]!RESET!
) else (
    echo   - Privilege level   : !RED!Standard User [Non-Elevated - Run setup/diagnostics as Admin]!RESET!
)

for /f "tokens=4-5 delims=. " %%a in ('ver') do set "WIN_VER=%%a.%%b"
echo   - Windows Version   : %WIN_VER%

powershell -NoProfile -Command "exit 0" >nul 2>&1
if !errorlevel! equ 0 (
    echo   - PowerShell status : !GREEN!Available!RESET!
    for /f "usebackq delims=" %%a in (`powershell -NoProfile -Command "Get-ExecutionPolicy"`) do set "PS_POLICY=%%a"
    echo   - PS Exec Policy    : !YELLOW!!PS_POLICY!!RESET!
) else (
    echo   - PowerShell status : !RED!Not working / Blocked!RESET!
)
echo.

REM ============================================================================
REM  2. Python Verification
REM ============================================================================
echo !CYAN!  [2] Checking Python Installation...!RESET!

set "PY_OK=0"
set "DETECTED_PY="
if exist "!PYDIR!\python.exe" set "DETECTED_PY=!PYDIR!\python.exe"
if not defined DETECTED_PY if exist "!PYDIR!\bin\python.exe" set "DETECTED_PY=!PYDIR!\bin\python.exe"

if defined DETECTED_PY (
    echo   - Portable Python   : !GREEN!Found at !DETECTED_PY!!RESET!
    REM Check if Tkinter is available
    "!DETECTED_PY!" -c "import tkinter" >nul 2>&1
    if !errorlevel! equ 0 (
        echo   - Tkinter [GUI]     : !GREEN!Available [GUI launcher will work]!RESET!
        set "PY_OK=1"
    ) else (
        echo   - Tkinter [GUI]     : !RED!Missing [GUI launcher will fail to open]!RESET!
    )
) else (
    echo   - Portable Python   : !YELLOW!Not installed in !PYDIR!!RESET!
)

where python >nul 2>&1
if !errorlevel! equ 0 (
    for /f "usebackq delims=" %%a in (`where python`) do set "SYSTEM_PY=%%a"
    echo   - System PATH Python: !GREEN!Found at !SYSTEM_PY!!RESET!
) else (
    echo   - System PATH Python: !YELLOW!None found on PATH!RESET!
)
echo.

REM ============================================================================
REM  3. Tor Verification
REM ============================================================================
echo !CYAN!  [3] Checking Tor Installation...!RESET!

set "TOR_EXE="
if exist "%TORDIR%\Tor\tor.exe" set "TOR_EXE=%TORDIR%\Tor\tor.exe"
if not defined TOR_EXE if exist "%TORDIR%\tor\tor.exe" set "TOR_EXE=%TORDIR%\tor\tor.exe"
if not defined TOR_EXE if exist "%TORDIR%\tor.exe" set "TOR_EXE=%TORDIR%\tor.exe"

if defined TOR_EXE (
    echo   - Tor executable    : !GREEN!Found at !TOR_EXE!!RESET!
    REM Check pluggable transports
    set "PT_DIR="
    for /d /r "%TORDIR%" %%d in (*PluggableTransports*) do (
        set "PT_DIR=%%d"
    )
    if defined PT_DIR (
        echo   - Transports folder : !GREEN!Found at !PT_DIR!!RESET!
        if exist "!PT_DIR!\lyrebird.exe" (
            echo   - Lyrebird [obfs4]  : !GREEN!Available!RESET!
        ) else (
            echo   - Lyrebird [obfs4]  : !YELLOW!Missing [some bridges won't work]!RESET!
        )
    ) else (
        echo   - Transports folder : !RED!Not found [pluggable bridges will fail]!RESET!
    )
) else (
    echo   - Tor executable    : !RED!NOT FOUND inside %TORDIR%!RESET!
)
echo.

REM ============================================================================
REM  4. Environment Variables & PATH Check
REM ============================================================================
echo !CYAN!  [4] Checking Environment Variables and PATH...!RESET!

set "ENV_PY="
for /f "usebackq tokens=2* delims= " %%a in (`reg query "HKLM\System\CurrentControlSet\Control\Session Manager\Environment" /v TORCHAIN_PYTHON 2^>nul ^| findstr /i "TORCHAIN_PYTHON"`) do set "ENV_PY=%%b"
if defined ENV_PY (
    echo   - TORCHAIN_PYTHON   : !GREEN!!ENV_PY!!RESET!
) else (
    echo   - TORCHAIN_PYTHON   : !RED!Not defined in Machine variables!RESET!
)

set "ENV_TOR="
for /f "usebackq tokens=2* delims= " %%a in (`reg query "HKLM\System\CurrentControlSet\Control\Session Manager\Environment" /v TORCHAIN_TOR 2^>nul ^| findstr /i "TORCHAIN_TOR"`) do set "ENV_TOR=%%b"
if defined ENV_TOR (
    echo   - TORCHAIN_TOR      : !GREEN!!ENV_TOR!!RESET!
) else (
    echo   - TORCHAIN_TOR      : !RED!Not defined in Machine variables!RESET!
)

REM Check PATH
powershell -NoProfile -Command ^
  "$path = [Environment]::GetEnvironmentVariable('Path', 'Machine'); " ^
  "if ($path -like '*%APPDIR%*') { exit 0 } else { exit 1 }"
if !errorlevel! equ 0 (
    echo   - torchain on PATH  : !GREEN!Yes!RESET!
) else (
    echo   - torchain on PATH  : !RED!No [Cannot run 'torchain' from command prompt]!RESET!
)
echo.

REM ============================================================================
REM  5. Network, Proxy & Firewall Check
REM ============================================================================
echo !CYAN!  [5] Checking Proxy Settings ^& Firewall Rules...!RESET!

REM Registry Proxy Settings
set "PROXY_ENABLED=0"
for /f "usebackq tokens=2* delims= " %%a in (`reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyEnable 2^>nul ^| findstr /i "ProxyEnable"`) do (
    set /a "PROXY_ENABLED=%%b"
)
if "!PROXY_ENABLED!"=="1" (
    echo   - Proxy Status      : !YELLOW!Enabled!RESET!
    
    set "PROXY_SERVER="
    for /f "usebackq tokens=2* delims= " %%a in (`reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyServer 2^>nul ^| findstr /i "ProxyServer"`) do set "PROXY_SERVER=%%b"
    if defined PROXY_SERVER (
        echo   - Proxy Server      : !GREEN!!PROXY_SERVER!!RESET!
    ) else (
        echo   - Proxy Server      : !RED!Not defined!RESET!
    )
) else (
    echo   - Proxy Status      : !GREEN!Disabled [Normal Internet Mode]!RESET!
)

set "PAC_URL="
for /f "usebackq tokens=2* delims= " %%a in (`reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v AutoConfigURL 2^>nul ^| findstr /i "AutoConfigURL"`) do set "PAC_URL=%%b"
if defined PAC_URL (
    echo   - Proxy PAC URL     : !YELLOW!!PAC_URL!!RESET!
)

REM Firewall rules check
netsh advfirewall show allprofiles state | findstr /i "ON" >nul 2>&1
if !errorlevel! equ 0 (
    echo   - Windows Firewall  : !GREEN!Enabled!RESET!
) else (
    echo   - Windows Firewall  : !YELLOW!Disabled [Not protected by system firewall]!RESET!
)

netsh advfirewall firewall show rule name="torchain-allow-tor" >nul 2>&1
if !errorlevel! equ 0 (
    echo   - Rule: allow-tor   : !GREEN!Configured!RESET!
) else (
    echo   - Rule: allow-tor   : !YELLOW!Not found [Outbound connections might be blocked]!RESET!
)

netsh advfirewall firewall show rule name="torchain-allow-loopback" >nul 2>&1
if !errorlevel! equ 0 (
    echo   - Rule: loopback    : !GREEN!Configured!RESET!
) else (
    echo   - Rule: loopback    : !YELLOW!Not found [Local proxy communication might fail]!RESET!
)
echo.

REM ============================================================================
REM  6. Processes & Port Binding Check
REM ============================================================================
echo !CYAN!  [6] Checking Active Processes and Ports...!RESET!

tasklist /FI "IMAGENAME eq tor.exe" 2>nul | findstr /I "tor.exe" >nul
if !errorlevel! equ 0 (
    echo   - tor.exe process   : !GREEN!Running!RESET!
) else (
    echo   - tor.exe process   : !YELLOW!Not running!RESET!
)

tasklist /FI "IMAGENAME eq python.exe" 2>nul | findstr /I "python.exe" >nul
if !errorlevel! equ 0 (
    echo   - python.exe process: !GREEN!Running [python processes active]!RESET!
) else (
    echo   - python.exe process: !YELLOW!Not running!RESET!
)

REM Check listening ports [SOCKS 9050, Control 9051, HTTP Proxy 8118]
netstat -ano | findstr ":9050" | findstr "LISTENING" >nul 2>&1
if !errorlevel! equ 0 (
    echo   - Port 9050 [SOCKS] : !GREEN!Listening!RESET!
) else (
    echo   - Port 9050 [SOCKS] : !YELLOW!Not listening!RESET!
)

netstat -ano | findstr ":9051" | findstr "LISTENING" >nul 2>&1
if !errorlevel! equ 0 (
    echo   - Port 9051 [Control]: !GREEN!Listening!RESET!
) else (
    echo   - Port 9051 [Control]: !YELLOW!Not listening!RESET!
)

if exist "%ProgramData%\torchain\logs\tor.log" (
    echo.
    echo !CYAN!  === Last 15 lines of Tor Log [excluding control noise] ===!RESET!
    powershell -NoProfile -Command "Get-Content -Path '%ProgramData%\torchain\logs\tor.log' 2>$null | Where-Object { $_ -notlike '*control connection*' } | Select-Object -Last 15"
    echo !CYAN!  ========================================================================!RESET!
)
echo.

REM ============================================================================
REM  Summary verdict
REM ============================================================================
echo !CYAN!  ======================================================!RESET!
echo !CYAN!   Diagnostic Summary!RESET!
echo !CYAN!  ======================================================!RESET!

set "ISSUES=0"

if not exist "!APPDIR!\tcwin\__init__.py" (
    echo   [!RED!FAIL!RESET!] torchain files are missing in !APPDIR!\tcwin.
    set /a ISSUES+=1
)
if not defined TOR_EXE (
    echo   [!RED!FAIL!RESET!] tor.exe is missing. Run setup.bat to extract it.
    set /a ISSUES+=1
)
if "!PY_OK!"=="0" (
    echo   [!RED!FAIL!RESET!] Python or GUI support [Tkinter] is not configured correctly in !PYDIR!.
    set /a ISSUES+=1
)
if not defined ENV_PY (
    echo   [!YELLOW!WARN!RESET!] TORCHAIN_PYTHON variable is missing. Environment is not registered.
    set /a ISSUES+=1
)
if not defined ENV_TOR (
    echo   [!YELLOW!WARN!RESET!] TORCHAIN_TOR variable is missing. Environment is not registered.
    set /a ISSUES+=1
)

if "!ISSUES!"=="0" (
    echo.
    echo   !GREEN!All core components look perfect. Torchain is ready to run.!RESET!
    echo.
    pause
) else (
    echo.
    echo   !YELLOW!Found !ISSUES! potential issues. Review findings above.!RESET!
    
    set "SETUP_PATH="
    if exist "%~dp0setup.bat" set "SETUP_PATH=%~dp0setup.bat"
    if not defined SETUP_PATH if exist "%~dp0..\setup.bat" set "SETUP_PATH=%~dp0..\setup.bat"
    if not defined SETUP_PATH if exist "%~dp0..\windows\setup.bat" set "SETUP_PATH=%~dp0..\windows\setup.bat"
    
    if defined SETUP_PATH (
        echo.
        set /p "RUN_SETUP=Would you like to run setup.bat now to resolve these issues? [Y/N]: "
        if /i "!RUN_SETUP!"=="Y" (
            echo.
            echo !CYAN!  ==^> Executing setup.bat...!RESET!
            echo.
            call "!SETUP_PATH!"
            echo.
            echo !CYAN!  ==^> Setup execution completed. Press any key to re-run diagnostics...!RESET!
            pause >nul
            goto :begin
        )
    ) else (
        echo.
        pause
    )
)
exit /b 0

