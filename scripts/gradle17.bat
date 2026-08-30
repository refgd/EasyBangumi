@echo off
setlocal

set "PROJECT_ROOT=%~dp0.."

if defined EASYBANGUMI_JAVA_HOME (
    set "JAVA_HOME=%EASYBANGUMI_JAVA_HOME%"
) else if exist "%USERPROFILE%\.jdks\corretto-17.0.13\bin\java.exe" (
    set "JAVA_HOME=%USERPROFILE%\.jdks\corretto-17.0.13"
) else if not exist "%JAVA_HOME%\bin\java.exe" (
    echo JDK 17 not found.
    echo Set EASYBANGUMI_JAVA_HOME to a JDK 17 directory and retry.
    exit /b 2
)

if "%~1"=="" (
    call "%PROJECT_ROOT%\gradlew.bat" :app:assembleDebug
) else (
    call "%PROJECT_ROOT%\gradlew.bat" %*
)

exit /b %ERRORLEVEL%
