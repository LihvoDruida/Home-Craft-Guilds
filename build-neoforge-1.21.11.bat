@echo off
setlocal
cd /d "%~dp0"
if exist gradlew.bat (
  call gradlew.bat -PmcProfile=1.21.11 -PtargetLoader=neoforge clean buildNeoForge --no-daemon
) else (
  gradle -PmcProfile=1.21.11 -PtargetLoader=neoforge clean buildNeoForge --no-daemon
)
pause
