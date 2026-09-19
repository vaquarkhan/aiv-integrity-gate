#!/usr/bin/env bash
# AIV pre-commit / local shift-left runner.
# Default: gate only staged changes (index tree vs HEAD). Override with AIV_DIFF_BASE / AIV_DIFF_HEAD.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "${AIV_WORKSPACE:-.}"

VERSION="${AIV_VERSION:-1.0.4}"
JAR="${AIV_CLI_JAR:-}"
if [[ -z "$JAR" ]]; then
  if [[ -f "./aiv-cli.jar" ]]; then
    JAR="./aiv-cli.jar"
  elif [[ -f "$ROOT/aiv-cli/target/aiv-cli-${VERSION}.jar" ]]; then
    JAR="$ROOT/aiv-cli/target/aiv-cli-${VERSION}.jar"
  else
    CACHE_DIR="${XDG_CACHE_HOME:-${HOME}/.cache}/aiv"
    mkdir -p "$CACHE_DIR"
    JAR="$CACHE_DIR/aiv-cli-${VERSION}.jar"
    if [[ ! -f "$JAR" ]]; then
      URL="https://repo1.maven.org/maven2/io/github/vaquarkhan/aiv/aiv-cli/${VERSION}/aiv-cli-${VERSION}.jar"
      echo "AIV: downloading CLI $VERSION from Maven Central..."
      curl -fsSL -o "$JAR" "$URL"
    fi
  fi
fi

if ! command -v java >/dev/null 2>&1; then
  echo "AIV: java not found (need JDK 17+)" >&2
  exit 1
fi

BASE="${AIV_DIFF_BASE:-}"
HEAD_REF="${AIV_DIFF_HEAD:-}"

if [[ -z "$BASE" || -z "$HEAD_REF" ]]; then
  if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "AIV: not a git repo; set AIV_DIFF_BASE and AIV_DIFF_HEAD" >&2
    exit 1
  fi
  if git diff --cached --quiet; then
    echo "AIV: no staged changes; skipping."
    exit 0
  fi
  BASE="HEAD"
  TREE="$(git write-tree)"
  HEAD_REF="$(git commit-tree "$TREE" -p HEAD -m "aiv-pre-commit-index")"
fi

exec java -jar "$JAR" --quiet --workspace . --diff "$BASE" --head "$HEAD_REF"
