#!/usr/bin/env python3
"""
Evidence demo on the public aiv-airflow-bench corpus: run the v2 CLI on every branch with --audit-log so
all runs land in one hash-chained log, verify it, build the HTML evidence report, and query the JSONL export
with DuckDB (optional).

Labels for the calibration table are derived from the bench's own branch names (labeled/proper-* has no
real defect, so a blocking finding there is a false positive; a finding on labeled/slop-* is a true
positive). That is the same circular ground truth the bench already has; it demonstrates the calibration
mechanics, not the precision of any rule on real pull requests.

Usage (from aiv-integrity-gate/):
  python benchmarks/airflow-bench-compare/run_audit_demo.py --bench ../aiv-airflow-bench \
      --jar aiv-cli/target/aiv-cli-1.0.4.jar --out benchmarks/airflow-bench-compare/results-audit
"""
import argparse
import json
import subprocess
from pathlib import Path


def sh(args, cwd, timeout=900):
    p = subprocess.run(args, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    return p.returncode, p.stdout, p.stderr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bench", required=True)
    ap.add_argument("--jar", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    bench, jar, out = Path(a.bench).resolve(), Path(a.jar).resolve(), Path(a.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    log = out / "aiv-audit.log"
    if log.exists():
        log.unlink()

    _, txt, _ = sh(["git", "branch", "-r", "--format=%(refname:short)"], cwd=bench)
    branches = sorted(b.strip().replace("origin/", "", 1) for b in txt.splitlines()
                      if b.strip() and "HEAD" not in b and b.strip() != "origin/main")
    labels = ["rule_id,file,line,verdict"]
    for i, br in enumerate(branches, 1):
        sh(["git", "checkout", "-q", "-f", "-B", "bench-run", f"origin/{br}"], cwd=bench)
        sh(["git", "clean", "-fdq", "-e", ".aiv"], cwd=bench)
        rep = out / "last.json"
        code, so, se = sh(["java", "-jar", str(jar), "--workspace", str(bench), "--diff", "main", "--head", "HEAD",
                           "--audit-log", str(log), "--output-json", str(rep), "--quiet"], cwd=bench)
        data = json.loads(rep.read_text(encoding="utf-8")) if rep.exists() else {}
        for g in data.get("gates", []):
            for f in g.get("findings", []):
                verdict = None
                rule = f.get("rule_id") or ""
                if br.startswith("labeled/proper-") and g.get("blocks_ci"):
                    verdict = "fp"
                elif br.startswith("labeled/slop-") and rule.startswith(("invariant.", "design.")):
                    # the slop fixtures seed marker defects only; other rules firing there are not ground truth
                    verdict = "tp"
                if verdict:
                    labels.append(f"{f.get('rule_id')},{f.get('file')},{f.get('start_line', 1)},{verdict}")
        print(f"[{i}/{len(branches)}] {br}: exit={code}", flush=True)
    sh(["git", "checkout", "-q", "-f", "main"], cwd=bench)
    (out / "labels.csv").write_text("\n".join(labels) + "\n", encoding="utf-8")

    code, so, se = sh(["java", "-jar", str(jar), "audit", "verify", str(log)], cwd=out)
    (out / "verify.txt").write_text(so + se, encoding="utf-8")
    code, so, se = sh(["java", "-jar", str(jar), "report", "--audit-log", str(log), "--out", str(out / "report.html"),
                       "--labels", str(out / "labels.csv"), "--min-labeled", "5", "--export-jsonl", str(out / "jsonl")], cwd=out)
    (out / "report.txt").write_text(so + se, encoding="utf-8")
    try:
        import duckdb  # optional
        con = duckdb.connect()
        f = str(out / "jsonl" / "findings.jsonl").replace("\\", "/")
        r = str(out / "jsonl" / "records.jsonl").replace("\\", "/")
        rows = con.execute(f"select rule_id, count(*) n, count(distinct file) files, sum(case when blocks_ci then 1 else 0 end) blocking "
                           f"from read_json_auto('{f}') group by rule_id order by n desc").fetchall()
        ov = con.execute(f"select sum(case when overrides.config_change then 1 else 0 end), "
                         f"sum(case when overrides.skip_ignored then 1 else 0 end), sum(len(overrides.suppressed)) "
                         f"from read_json_auto('{r}') where schema = 'aiv-gate-run/1'").fetchall()
        lines = ["| rule_id | findings | files | blocking |", "|---|---|---|---|"]
        lines += [f"| `{x[0]}` | {x[1]} | {x[2]} | {x[3]} |" for x in rows]
        lines += ["", f"Overrides (config_change, skip_ignored, suppressions): {ov[0]}"]
        (out / "duckdb.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
        print("duckdb summary written")
    except ImportError:
        print("duckdb not installed; skipped the SQL step")


if __name__ == "__main__":
    main()
