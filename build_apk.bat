@echo off
REM Build, install and smoke-test the Android app.
REM
REM Usage:  build_apk.bat            build debug + release, install debug
REM         build_apk.bat release    build release only, do not install
REM         build_apk.bat install    install the last built APK only
REM
REM Requirements: JDK 17+ (JAVA_HOME), Android SDK with platform 35 and
REM build-tools 34+, and a device with USB debugging enabled.
setlocal

cd /d "%~dp0"

set SDK=%LOCALAPPDATA%\Android\Sdk
set ADB=%SDK%\platform-tools\adb.exe
set GRADLE=%USERPROFILE%\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat
if not exist "%GRADLE%" set GRADLE=gradle

if "%JAVA_HOME%"=="" set JAVA_HOME=D:\Java\jdk-21

set MODE=%1
if "%MODE%"=="" set MODE=all

if "%MODE%"=="install" goto :install
if "%MODE%"=="release" goto :release

echo [1/3] building debug...
call "%GRADLE%" :app:assembleDebug --console=plain
if errorlevel 1 goto :fail

:release
echo [2/3] building release...
call "%GRADLE%" :app:assembleRelease --console=plain
if errorlevel 1 goto :fail

:install
echo [3/3] installing debug APK...
"%ADB%" install -r app\build\outputs\apk\debug\app-debug.apk
if errorlevel 1 goto :fail

REM The launcher can show an "open with" dialog when the phone has an app
REM clone (secondary user) of this package.  Disabling the clone's copy makes
REM the launch unambiguous; it is a device-side convenience, not an app fix.
"%ADB%" shell pm disable-user --user 900 com.spectradesk.camera >nul 2>&1

echo.
echo Launching...
"%ADB%" shell am start -n com.spectradesk.camera/.MainActivity
echo.
echo APKs:
echo   debug   app\build\outputs\apk\debug\app-debug.apk
echo   release app\build\outputs\apk\release\app-release.apk
goto :eof

:fail
echo.
echo BUILD OR INSTALL FAILED
exit /b 1
