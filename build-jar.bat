@echo off
setlocal

pushd "%~dp0"

tasklist /FI "IMAGENAME eq Bookmap.exe" 2>nul | find /I "Bookmap.exe" >nul
if not errorlevel 1 (
    echo Bookmap is running and may have the existing plugin JAR locked.
    echo Close Bookmap, then run this script again.
    popd
    exit /b 1
)

echo Building the Bookmap plugin release JAR...
call gradlew.bat --stop >nul 2>&1
call gradlew.bat clean build
set "BUILD_EXIT_CODE=%ERRORLEVEL%"

if not "%BUILD_EXIT_CODE%"=="0" (
    echo.
    echo Build failed with exit code %BUILD_EXIT_CODE%.
    popd
    exit /b %BUILD_EXIT_CODE%
)

echo.
echo Build completed successfully:
for %%F in ("build\libs\*.jar") do echo   %%~fF

popd
exit /b 0
