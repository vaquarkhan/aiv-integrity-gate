#!/bin/bash
# Validate AIV by running it on the example project
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mvn -B -ntp clean verify -pl aiv-cli -am
VERSION=$(mvn -q -DforceStdout -Dexpression=project.version help:evaluate)
echo "Running AIV (aiv-cli-${VERSION}.jar)..."
JAR="$ROOT/aiv-cli/target/aiv-cli-${VERSION}.jar"
java -jar "$JAR" --workspace "$ROOT" --diff origin/main
