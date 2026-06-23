@echo off
for /f "delims=" %%a in ('powershell -NoProfile -Command "[char]27"') do set "ESC=%%a"
if not "%ESC:~1%"=="" set "ESC="
echo ESC is: [%ESC%]
if defined ESC (
    echo ESC is set correctly!
) else (
    echo ESC is NOT set!
)
