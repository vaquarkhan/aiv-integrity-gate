#!/usr/bin/env bash
# Sets action.yml inputs.aiv-version default to the reactor version from pom.xml (run from repo root or via scripts/).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VER="$(cd "$ROOT" && mvn -q -DforceStdout help:evaluate -Dexpression=project.version -f pom.xml)"
if [[ -z "${VER}" ]]; then
  echo "Could not read project.version from Maven" >&2
  exit 1
fi
# Replace default under aiv-version (multiline block)
perl -i -0pe "s/(aiv-version:\s*\n\s+description:[^\n]*\n\s+required:[^\n]*\n\s+default: ')[0-9.]+(')/\${1}${VER}\${2}/s" "${ROOT}/action.yml"
echo "action.yml: inputs.aiv-version default set to ${VER}"
