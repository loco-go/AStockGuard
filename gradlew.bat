@echo off
setlocal
set APP_HOME=%~dp0
set DIST_DIR=%APP_HOME%.gradle-dist\gradle-8.9
set ZIP=%APP_HOME%.gradle-dist\gradle-8.9-bin.zip
if exist "%DIST_DIR%\bin\gradle.bat" goto run
if not exist "%APP_HOME%.gradle-dist" mkdir "%APP_HOME%.gradle-dist"
if not exist "%ZIP%" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' -OutFile '%ZIP%'"
  if errorlevel 1 exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%ZIP%' -DestinationPath '%APP_HOME%.gradle-dist' -Force"
if errorlevel 1 exit /b 1
:run
call "%DIST_DIR%\bin\gradle.bat" %*
endlocal
