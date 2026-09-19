#!/usr/bin/env bash
# Score Airflow benchmark fixtures with a local aiv-cli JAR.
# Run from aiv-integrity-gate repo root:
#   ./benchmarks/airflow/scripts/run-benchmark.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
BENCH="$ROOT/benchmarks/airflow"
RESULTS="$BENCH/results"
mkdir -p "$RESULTS"

VERSION="$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version -f "$ROOT/pom.xml" | tr -d '\r')"
JAR="$ROOT/aiv-cli/target/aiv-cli-${VERSION}.jar"
if [[ ! -f "$JAR" ]]; then
  echo "Building aiv-cli..."
  (cd "$ROOT" && mvn -B -ntp -pl aiv-cli -am package -DskipTests)
fi
[[ -f "$JAR" ]] || { echo "Missing $JAR"; exit 1; }

WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

cd "$WORK"
git init -q
git config user.email "bench@aiv.local"
git config user.name "AIV Bench"
cp -R "$BENCH/.aiv" .aiv
mkdir -p providers
: > providers/__init__.py
git add -A
git commit -q -m "base"
BASE="$(git rev-parse HEAD)"

python3 - <<'PY' "$BENCH/corpus/cases.json" "$BENCH" "$WORK" "$BASE" "$JAR" "$RESULTS" "$VERSION"
import json, os, shutil, subprocess, sys, datetime
cases_path, bench, work, base, jar, results, version = sys.argv[1:8]
data = json.load(open(cases_path, encoding="utf-8"))
rows = []
for case in data["cases"]:
    if case.get("source") != "fixture":
        continue
    src = os.path.join(bench, case["path"])
    if not os.path.isfile(src):
        print("Missing", case["path"], file=sys.stderr)
        continue
    subprocess.check_call(["git", "checkout", "-q", base], cwd=work)
    subprocess.check_call(["git", "clean", "-fdq"], cwd=work)
    dest_rel = case["path"].replace("fixtures/", "providers/bench/", 1)
    dest = os.path.join(work, dest_rel)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    shutil.copy2(src, dest)
    subprocess.check_call(["git", "add", "-A"], cwd=work)
    subprocess.check_call(["git", "commit", "-q", "-m", f"case {case['id']}"], cwd=work)
    out = os.path.join(results, f"case-{case['id']}.json")
    proc = subprocess.run(
        ["java", "-jar", jar, "--workspace", work, "--diff", base, "--head", "HEAD",
         "--quiet", "--output-json", out],
        cwd=work,
    )
    rows.append({
        "id": case["id"],
        "expected": case["expected"],
        "exit_code": proc.returncode,
        "report": f"results/case-{case['id']}.json",
    })
    print(f"{case['id']}: exit={proc.returncode} expected={case['expected']}")
summary = {
    "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "aiv_version": version,
    "jar": jar,
    "cases": rows,
}
open(os.path.join(results, "summary.json"), "w", encoding="utf-8").write(json.dumps(summary, indent=2))
print("Wrote", os.path.join(results, "summary.json"))
PY
