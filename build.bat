@echo off
rem Сборка APK: результат — NeProspi.apk в этой папке
set JAVA_HOME=C:\Users\user\android-tools\jdk-17.0.20.1+1
set PATH=%JAVA_HOME%\bin;%PATH%
call gradlew.bat assembleDebug || exit /b 1
copy /Y app\build\outputs\apk\debug\app-debug.apk NeProspi.apk
