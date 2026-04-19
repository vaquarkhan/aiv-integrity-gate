#!/usr/bin/env bash
# Download the shaded AIV CLI from Maven Central (no clone/build).
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/vaquarkhan/aiv-integrity-gate/main/scripts/install-aiv.sh | bash -s -- 1.0.3
#   AIV_VERSION=1.0.3 ./scripts/install-aiv.sh
set -euo pipefail
VERSION="${1:-${AIV_VERSION:-1.0.3}}"
BASE="${MAVEN_CENTRAL_BASE:-https://repo1.maven.org/maven2}"
URL="$BASE/io/github/vaquarkhan/aiv/aiv-cli/$VERSION/aiv-cli-$VERSION.jar"
OUT="${AIV_INSTALL_DIR:-.}/aiv-cli.jar"
echo "Downloading AIV CLI $VERSION from Maven Central..."
echo "  $URL"
curl -fsSL -o "$OUT" "$URL"
echo "Wrote $OUT"
echo "Run: java -jar $OUT --diff origin/main"
