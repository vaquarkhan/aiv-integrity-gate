@echo off
cd /d "%~dp0\.."
call mvn clean package -DskipTests -B -q
echo Running AIV on example-project...
java -jar aiv-cli\target\aiv-cli-1.0.0-SNAPSHOT.jar --workspace . --diff origin/main
