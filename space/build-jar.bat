@echo off
chcp 65001 >nul
rem Сборка Phase Space: нужен JDK 25 (java -version). Готовый jar окажется рядом: phase-space.jar
cd /d "%~dp0"
call gradlew.bat build -x test
if errorlevel 1 (
  echo.
  echo Сборка не удалась. Пришлите build\reports или текст ошибки выше.
  pause
  exit /b 1
)
for %%f in (build\libs\phase-space-*.jar) do (
  echo %%f | findstr /i /v sources >nul && copy /y "%%f" phase-space.jar >nul
)
echo.
echo Готово: %~dp0phase-space.jar
pause
