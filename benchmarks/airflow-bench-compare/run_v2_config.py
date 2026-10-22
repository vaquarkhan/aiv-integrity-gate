#!/usr/bin/env python3
"""
Second pass over aiv-airflow-bench with the v2 *airflow preset* configuration.

run_compare.py judges every branch with the bench repository's own .aiv/config.yaml (read from main),
which enables only the 1.0.4-era gates. This script answers a different question: what does v2 report
on the same corpus when a project adopts the shipped `airflow` preset (rules + airflow pack, external
ruff AIR, test-adequacy, package-existence, security, pr-metadata, cohesion, duplication; all advisory)?

The preset is written into the working tree and read with --config-from-workspace, so the base-ref
trust boundary is deliberately bypassed here (this is a measurement, not a gate run).

Usage (from aiv-integrity-gate/):
  python benchmarks/airflow-bench-compare/run_v2_config.py \
      --bench ../aiv-airflow-bench --jar aiv-cli/target/aiv-cli-1.0.4.jar \
      --preset aiv-cli/src/main/resources/presets/airflow/config.yaml \
      --out benchmarks/airflow-bench-compare/results-v2config
"""
import argparse
import json
import shutil
import subprocess
import time
from collections import Counter
from pathlib import Path


def sh(args, cwd, timeout=600):
    p = subprocess.run(args, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    return p.returncode, p.stdout, p.stderr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bench", required=True)
    ap.add_argument("--jar", required=True)
    ap.add_argument("--preset", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--only", default="")
    a = ap.parse_args()
    bench, jar, out = Path(a.bench).resolve(), Path(a.jar).resolve(), Path(a.out).resolve()
    preset_text = Path(a.preset).read_text(encoding="utf-8")
    (out / "reports").mkdir(parents=True, exist_ok=True)

    _, txt, _ = sh(["git", "branch", "-r", "--format=%(refname:short)"], cwd=bench)
    branches = sorted(b.strip().replace("origin/", "", 1) for b in txt.splitlines()
                      if b.strip().startswith("origin/") and "HEAD" not in b and b.strip() != "origin/main")
    if a.only:
        branches = [b for b in branches if a.only in b]
    print(f"{len(branches)} branches", flush=True)

    rows = []
    for i, br in enumerate(branches, 1):
        sh(["git", "checkout", "-q", "-f", "-B", "bench-run", f"origin/{br}"], cwd=bench)
        sh(["git", "clean", "-fdq", "-e", ".aiv"], cwd=bench)
        (bench / ".aiv").mkdir(exist_ok=True)
        (bench / ".aiv" / "config.yaml").write_text(preset_text, encoding="utf-8")
        report = out / "reports" / (br.replace("/", "__") + ".json")
        if report.exists():
            report.unlink()
        t0 = time.time()
        code, so, se = sh(["java", "-jar", str(jar), "--workspace", str(bench), "--diff", "main", "--head", "HEAD",
                           "--config-from-workspace", "--output-json", str(report), "--quiet"], cwd=bench)
        dt = time.time() - t0
        data = json.loads(report.read_text(encoding="utf-8")) if report.exists() else {}
        rules = []
        soft = []
        for g in data.get("gates", []):
            if g.get("passed"):
                continue
            soft.append(g["id"])
            for f in g.get("findings", []):
                rules.append(f.get("rule_id") or g["id"])
        rows.append({"branch": br, "exit": code, "secs": round(dt, 1), "gates": soft, "rules": rules,
                     "notices": data.get("notices", [])})
        print(f"[{i}/{len(branches)}] {br}: exit={code} gates={','.join(soft)}", flush=True)

    sh(["git", "checkout", "-q", "-f", "main"], cwd=bench)
    sh(["git", "checkout", "-q", "--", ".aiv/config.yaml"], cwd=bench)

    (out / "results.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")
    cohorts = {}
    for r in rows:
        c = r["branch"].split("/")[0] if not r["branch"].startswith("abandoned/") else "abandoned/" + r["branch"].split("/")[1]
        cohorts.setdefault(c, []).append(r)
    summary = {"branches": len(rows), "cohorts": {}}
    for c, rs in sorted(cohorts.items()):
        gate_counts = Counter(g for r in rs for g in set(r["gates"]))
        rule_counts = Counter(x for r in rs for x in r["rules"])
        summary["cohorts"][c] = {
            "n": len(rs),
            "exit_nonzero": sum(1 for r in rs if r["exit"] != 0),
            "any_signal": sum(1 for r in rs if r["gates"]),
            "any_signal_excl_syntax": sum(1 for r in rs if [g for g in r["gates"] if g != "syntax"]),
            "gate_counts": dict(gate_counts.most_common()),
            "rule_counts": dict(rule_counts.most_common()),
        }
    (out / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    lines = ["# v2 airflow preset on aiv-airflow-bench", "",
             "Measurement run: the shipped `airflow` preset (all gates advisory) written into the working tree and read with",
             "`--config-from-workspace`. The preset is advisory, so a non-zero exit is a defect in AIV, not a verdict;",
             "the question is what evidence each gate produces.", "",
             "| Cohort | n | non-zero exits | any signal | excl. syntax | gates fired (branches) | rules fired (findings) |",
             "|---|---|---|---|---|---|---|"]
    for c, s in summary["cohorts"].items():
        gates = ", ".join(f"{k} {v}" for k, v in s["gate_counts"].items()) or "none"
        rules = ", ".join(f"{k} {v}" for k, v in s["rule_counts"].items()) or "none"
        lines.append(f"| {c} | {s['n']} | {s['exit_nonzero']} | {s['any_signal']} | {s['any_signal_excl_syntax']} | {gates} | {rules} |")
    (out / "SUMMARY.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=1))


if __name__ == "__main__":
    main()
