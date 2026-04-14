@echo off
cd /d "%~dp0\.."
set ROOT=%cd%
call mvn -B -ntp clean verify -pl aiv-cli -am
echo Running AIV...
cd aiv-cli\target
java -jar aiv-cli-1.0.0-SNAPSHOT.jar --workspace "%ROOT%" --diff origin/main
