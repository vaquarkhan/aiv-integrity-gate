@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0\.."
set ROOT=%cd%
call mvn -B -ntp clean verify -pl aiv-cli -am
if errorlevel 1 exit /b 1
for /f "usebackq delims=" %%i in (`powershell -NoProfile -Command "([xml](Get-Content -Raw '%ROOT%\pom.xml')).project.version"`) do set VERSION=%%i
echo Running AIV (aiv-cli-!VERSION!.jar^)...
java -jar "%ROOT%\aiv-cli\target\aiv-cli-!VERSION!.jar" --workspace "%ROOT%" --diff origin/main
