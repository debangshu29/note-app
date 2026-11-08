@echo off
setlocal
cd /d "%~dp0"
title Building Note app
echo.
echo  ===============================
echo   Building the Note app (APK)
echo  ===============================
echo.

rem ---- find the Android SDK (installed with Android Studio)
if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_HOME (
  echo Android SDK not found.
  echo Install Android Studio once from https://developer.android.com/studio
  echo then run this file again.
  pause
  exit /b 1
)

rem ---- use the Java that comes with Android Studio
if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

> local.properties echo sdk.dir=%ANDROID_HOME:\=\\%

call gradlew.bat assembleRelease
if errorlevel 1 (
  echo.
  echo Build failed. Scroll up to see the error.
  pause
  exit /b 1
)

copy /y "app\build\outputs\apk\release\app-release.apk" "Note.apk" >nul
echo.
echo  Done!  Note.apk is now in this folder:
echo    %~dp0Note.apk
echo  Send it to your phone (USB, WhatsApp to yourself, Drive...) and tap it to install.
echo.
pause
