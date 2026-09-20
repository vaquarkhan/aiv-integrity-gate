#!/usr/bin/env bash
# Thin wrapper: downloads shaded JAR once, then runs java -jar.
set -euo pipefail
AIV_VERSION="${AIV_VERSION:-1.0.4}"
CACHE_DIR="${AIV_HOME:-${HOME}/.aiv}"
JAR="${AIV_CLI_JAR:-$CACHE_DIR/aiv-cli-${AIV_VERSION}.jar}"
mkdir -p "$(dirname "$JAR")"
if [[ ! -f "$JAR" ]]; then
  curl -fsSL -o "$JAR" \
    "https://repo1.maven.org/maven2/io/github/vaquarkhan/aiv/aiv-cli/${AIV_VERSION}/aiv-cli-${AIV_VERSION}.jar"
fi
exec java -jar "$JAR" "$@"
