#!/bin/bash
# Validate AIV by running it on the example project
set -e
cd "$(dirname "$0")/.."
mvn clean package -DskipTests -B -q
echo "Running AIV on example-project..."
java -jar aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar --workspace . --diff origin/main
