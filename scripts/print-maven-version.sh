#!/usr/bin/env bash
# Prints the reactor root <version> (same value as ${project.version} for the parent POM).
# Usage: ./scripts/print-maven-version.sh
# Or from repo root: bash scripts/print-maven-version.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec mvn -q -DforceStdout help:evaluate -Dexpression=project.version -f "$ROOT/pom.xml"
