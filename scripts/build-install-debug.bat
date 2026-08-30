@echo off
setlocal

set "PROJECT_ROOT=%~dp0.."
set "APK=%PROJECT_ROOT%\app\build\outputs\apk\debug\app-debug.apk"
set "PACKAGE=com.refgd.easybangumi4.debug"
set "ACTIVITY=com.heyanle.easybangumi4.splash.SplashActivity"

call "%~dp0gradle17.bat" :app:assembleDebug
if errorlevel 1 exit /b %ERRORLEVEL%

where adb >nul 2>nul
if errorlevel 1 (
    echo adb was not found in PATH.
    exit /b 3
)

adb get-state 1>nul 2>nul
if errorlevel 1 (
    echo No Android device is connected and authorized.
    exit /b 4
)

adb install -r "%APK%"
if errorlevel 1 exit /b %ERRORLEVEL%

adb shell am force-stop "%PACKAGE%"
adb shell am start -n "%PACKAGE%/%ACTIVITY%"
exit /b %ERRORLEVEL%
