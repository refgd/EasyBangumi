# Development Scripts

Run these commands from any working directory:

```bat
scripts\gradle17.bat :app:compileDebugKotlin
scripts\gradle17.bat :app:assembleDebug
scripts\build-install-debug.bat
```

`gradle17.bat` uses `EASYBANGUMI_JAVA_HOME` when set, then checks the project's
usual Corretto 17 location under `%USERPROFILE%\.jdks`. With no Gradle task it
builds the debug APK.

`build-install-debug.bat` builds, installs over the existing debug app while
preserving data, and launches the splash activity on the connected ADB device.
