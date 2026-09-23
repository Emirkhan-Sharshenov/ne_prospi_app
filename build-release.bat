@echo off
rem Сборка для Google Play: подписанный AAB + подписанный APK для проверки на телефоне.
rem Ключ и пароли берутся из keystore.properties (в git не попадает).
set JAVA_HOME=C:\Users\user\android-tools\jdk-17.0.20.1+1
set PATH=%JAVA_HOME%\bin;%PATH%
call gradlew.bat bundleRelease assembleRelease || exit /b 1
copy /Y app\build\outputs\bundle\release\app-release.aab NeProspi-release.aab
copy /Y app\build\outputs\apk\release\app-release.apk NeProspi.apk
echo.
echo Готово:
echo   NeProspi-release.aab  - загружать в Google Play
echo   NeProspi.apk          - ставить на телефон для проверки
