@echo off
chcp 65001 >nul
REM Build the Smart Kid TV (Morphe + Kids mode) patch bundle on Windows.
REM Requires: JDK 21 (or 17+), Android SDK (Android Studio), Internet access.
cd /d "%~dp0"

where java >nul 2>nul || (echo [Lỗi] Chưa cài Java. Cài JDK 21: https://adoptium.net & pause & exit /b 1)
if "%ANDROID_HOME%"=="" if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if "%ANDROID_HOME%"=="" (echo [Lỗi] Không tìm thấy Android SDK. Cài Android Studio rồi chạy lại. & pause & exit /b 1)
echo sdk.dir=%ANDROID_HOME:\=/%> local.properties

if "%GITHUB_ACTOR%"=="" set /p GITHUB_ACTOR=Ten tai khoan GitHub: 
if "%GITHUB_TOKEN%"=="" set /p GITHUB_TOKEN=GitHub token (read:packages): 

call gradlew.bat :patches:buildAndroid --no-daemon
if errorlevel 1 (echo [Lỗi] Build thất bại, xem log ở trên. & pause & exit /b 1)

echo.
echo Xong! File .mpp nằm trong patches\build\libs
explorer patches\build\libs
pause
