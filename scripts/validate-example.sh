#!/bin/bash
# Validate AIV by running it on the example project
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mvn -B -ntp clean verify -pl aiv-cli -am
echo "Running AIV..."
cd aiv-cli/target
java -jar aiv-cli-1.0.0-SNAPSHOT.jar --workspace "$ROOT" --diff origin/main
