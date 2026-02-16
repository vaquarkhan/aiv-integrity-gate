@echo off
cd /d "%~dp0\.."
set ROOT=%cd%
call mvn package -DskipTests -B -q -pl aiv-cli -am
echo Running AIV...
cd aiv-cli\target
java -jar aiv-cli-1.0.0-SNAPSHOT.jar --workspace "%ROOT%" --diff origin/main
