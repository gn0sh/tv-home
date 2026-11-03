@echo off
rem Double-click to install the newest signed TV Home build on the Google TV. Runs scripts\install-tv-home.ps1 as it is
rem (no policy override). The window stays open so you can read the last line.
powershell.exe -NoProfile -File "%~dp0install-tv-home.ps1" -Fetch
echo.
pause
