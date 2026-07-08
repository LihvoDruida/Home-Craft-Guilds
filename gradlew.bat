@echo off
setlocal EnableExtensions DisableDelayedExpansion

set "GRADLE_VERSION=8.14.3"
set "ROOT_DIR=%~dp0"
set "WRAP_DIR=%ROOT_DIR%.gradle-local"
set "GRADLE_HOME=%WRAP_DIR%\gradle-%GRADLE_VERSION%"
set "GRADLE_ZIP=%WRAP_DIR%\gradle-%GRADLE_VERSION%-bin.zip"
set "GRADLE_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"
set "GRADLE_BAT=%GRADLE_HOME%\bin\gradle.bat"

if not exist "%WRAP_DIR%" mkdir "%WRAP_DIR%"

if not exist "%GRADLE_BAT%" (
  echo [Home Craft] Gradle %GRADLE_VERSION% not found locally.
  echo [Home Craft] Downloading official Gradle distribution once...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%GRADLE_URL%' -OutFile '%GRADLE_ZIP%'"
  if errorlevel 1 (
    echo [ERROR] Failed to download Gradle. Check internet connection or firewall.
    exit /b 1
  )
  echo [Home Craft] Extracting Gradle...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Expand-Archive -Path '%GRADLE_ZIP%' -DestinationPath '%WRAP_DIR%' -Force"
  if errorlevel 1 (
    echo [ERROR] Failed to extract Gradle.
    exit /b 1
  )
)

call "%GRADLE_BAT%" %*
exit /b %ERRORLEVEL%
