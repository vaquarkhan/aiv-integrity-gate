#!/usr/bin/env python3
"""
Old-vs-new AIV comparison over the public aiv-airflow-bench corpus.

For every bench branch (labeled/*, abandoned/*, case/*, snapshot/*) this script:
  1. checks the branch out in the bench clone,
  2. runs the OLD shaded CLI and the NEW shaded CLI with the same base ref (main),
  3. stores both JSON reports and a side-by-side summary (CSV + Markdown + JSON).

Expected labels (ground truth) come from the branch name:
  labeled/proper-*        -> expect PASS
  labeled/slop-*          -> expect FAIL (objective markers)
  labeled/mixed-hard-*    -> expect FAIL
  labeled/mixed-soft-*    -> expect PASS (advisory only)
  labeled/mixed-cohesion-*-> expect PASS (cohesion advisory)
  case/clean-control      -> expect PASS ; other case/* -> FAIL
  abandoned/<label>/...   -> real closed-unmerged Airflow PRs; no hard ground truth, report what fires
  snapshot/*              -> real PR snapshots; report only

Usage (from aiv-integrity-gate/):
  python benchmarks/airflow-bench-compare/run_compare.py \
      --bench ../aiv-airflow-bench \
      --old   ../bench-compare/aiv-old/aiv-cli/target/aiv-cli-1.0.4.jar \
      --new   aiv-cli/target/aiv-cli-1.0.4.jar \
      --out   benchmarks/airflow-bench-compare/results
"""
import argparse
import csv
import json
import os
import subprocess
import sys
import time
from pathlib import Path


def sh(args, cwd, timeout=600):
    p = subprocess.run(args, cwd=cwd, capture_output=True, text=True, encoding="utf-8",
                       errors="replace", timeout=timeout)
    return p.returncode, p.stdout, p.stderr


def expected_for(branch: str):
    if branch.startswith("labeled/proper-"):
        return "PASS"
    if branch.startswith("labeled/slop-") or branch.startswith("labeled/mixed-hard-"):
        return "FAIL"
    if branch.startswith("labeled/mixed-soft-") or branch.startswith("labeled/mixed-cohesion-"):
        return "PASS"
    if branch == "case/clean-control":
        return "PASS"
    if branch.startswith("case/"):
        return "FAIL"
    return "n/a"


def cohort_for(branch: str):
    if branch.startswith("labeled/"):
        return "labeled"
    if branch.startswith("abandoned/"):
        return "abandoned/" + branch.split("/")[1]
    if branch.startswith("case/"):
        return "case"
    if branch.startswith("snapshot/"):
        return "snapshot"
    return "other"


def run_jar(jar: Path, bench: Path, report: Path, extra):
    args = ["java", "-jar", str(jar), "--workspace", str(bench), "--diff", "main", "--head", "HEAD",
            "--output-json", str(report), "--quiet"] + extra
    t0 = time.time()
    code, out, err = sh(args, cwd=bench)
    dt = time.time() - t0
    data = {}
    if report.exists():
        try:
            data = json.loads(report.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            data = {}
    return code, dt, data, (out + "\n" + err)[-4000:]


def summarize_gates(data):
    hard = []
    soft = []
    rules = set()
    for g in data.get("gates", []):
        if g.get("passed"):
            continue
        (hard if g.get("blocks_ci") else soft).append(g["id"])
        for f in g.get("findings", []):
            rules.add(f.get("rule_id") or f.get("ruleId") or g["id"])
    return hard, soft, sorted(rules)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bench", required=True)
    ap.add_argument("--old", required=True)
    ap.add_argument("--new", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--only", default="", help="substring filter on branch names")
    a = ap.parse_args()

    bench = Path(a.bench).resolve()
    old_jar = Path(a.old).resolve()
    new_jar = Path(a.new).resolve()
    out = Path(a.out).resolve()
    (out / "reports").mkdir(parents=True, exist_ok=True)

    code, branches_txt, _ = sh(["git", "branch", "-r", "--format=%(refname:short)"], cwd=bench)
    branches = sorted(b.strip().replace("origin/", "", 1) for b in branches_txt.splitlines()
                      if b.strip() and "HEAD" not in b and b.strip() != "origin/main")
    if a.only:
        branches = [b for b in branches if a.only in b]
    print(f"{len(branches)} branches", flush=True)

    rows = []
    for i, br in enumerate(branches, 1):
        safe = br.replace("/", "__")
        sh(["git", "checkout", "-q", "-f", "-B", "bench-run", f"origin/{br}"], cwd=bench)
        sh(["git", "clean", "-fdq", "-e", ".aiv"], cwd=bench)
        rep_old = out / "reports" / f"{safe}.old.json"
        rep_new = out / "reports" / f"{safe}.new.json"
        for r in (rep_old, rep_new):
            if r.exists():
                r.unlink()
        # OLD build has no base-ref pinning; NEW pins to main by default. Same config file either way.
        c_old, t_old, d_old, log_old = run_jar(old_jar, bench, rep_old, [])
        c_new, t_new, d_new, log_new = run_jar(new_jar, bench, rep_new, [])
        h_old, s_old, r_old = summarize_gates(d_old)
        h_new, s_new, r_new = summarize_gates(d_new)
        exp = expected_for(br)
        verdict_old = "FAIL" if c_old == 1 else ("PASS" if c_old == 0 else f"ERR{c_old}")
        verdict_new = "FAIL" if c_new == 1 else ("PASS" if c_new == 0 else f"ERR{c_new}")
        rows.append({
            "branch": br, "cohort": cohort_for(br), "expected": exp,
            "old_exit": c_old, "old_verdict": verdict_old, "old_hard": ";".join(h_old), "old_soft": ";".join(s_old),
            "old_rules": ";".join(r_old), "old_secs": round(t_old, 1),
            "new_exit": c_new, "new_verdict": verdict_new, "new_hard": ";".join(h_new), "new_soft": ";".join(s_new),
            "new_rules": ";".join(r_new), "new_secs": round(t_new, 1),
            "new_notices": " | ".join(d_new.get("notices", [])),
            "old_correct": (verdict_old == exp) if exp != "n/a" else "",
            "new_correct": (verdict_new == exp) if exp != "n/a" else "",
        })
        if c_old not in (0, 1):
            (out / "reports" / f"{safe}.old.log").write_text(log_old, encoding="utf-8")
        if c_new not in (0, 1):
            (out / "reports" / f"{safe}.new.log").write_text(log_new, encoding="utf-8")
        print(f"[{i}/{len(branches)}] {br}: old={verdict_old}({','.join(h_old)}) new={verdict_new}({','.join(h_new)})"
              f" soft_new={','.join(s_new)}", flush=True)

    sh(["git", "checkout", "-q", "-f", "main"], cwd=bench)

    with (out / "compare.csv").open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    (out / "compare.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")

    # ---- aggregate ----
    def agg(rows_, key):
        gt = [r for r in rows_ if r["expected"] != "n/a"]
        correct = sum(1 for r in gt if r[f"{key}_correct"] is True)
        tp = sum(1 for r in gt if r["expected"] == "FAIL" and r[f"{key}_verdict"] == "FAIL")
        fn = sum(1 for r in gt if r["expected"] == "FAIL" and r[f"{key}_verdict"] == "PASS")
        fp = sum(1 for r in gt if r["expected"] == "PASS" and r[f"{key}_verdict"] == "FAIL")
        tn = sum(1 for r in gt if r["expected"] == "PASS" and r[f"{key}_verdict"] == "PASS")
        return dict(n=len(gt), correct=correct, TP=tp, FN=fn, FP=fp, TN=tn,
                    recall=(tp / (tp + fn)) if (tp + fn) else None,
                    precision=(tp / (tp + fp)) if (tp + fp) else None)

    def cohort_table(prefix):
        sub = [r for r in rows if r["cohort"].startswith(prefix)]
        o_hard = sum(1 for r in sub if r["old_verdict"] == "FAIL")
        n_hard = sum(1 for r in sub if r["new_verdict"] == "FAIL")
        o_any = sum(1 for r in sub if r["old_hard"] or r["old_soft"])
        n_any = sum(1 for r in sub if r["new_hard"] or r["new_soft"])
        return dict(n=len(sub), old_hard_fail=o_hard, new_hard_fail=n_hard, old_any_signal=o_any, new_any_signal=n_any)

    summary = {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "old_jar": str(old_jar), "new_jar": str(new_jar), "branches": len(rows),
        "labeled_ground_truth": {"old": agg(rows, "old"), "new": agg(rows, "new")},
        "abandoned_real_prs": {
            "ai_disclosed": cohort_table("abandoned/ai_disclosed"),
            "ai_signal_advisory": cohort_table("abandoned/ai_signal_advisory"),
            "abandoned_clean": cohort_table("abandoned/abandoned_clean"),
            "all": cohort_table("abandoned/"),
        },
        "snapshots": cohort_table("snapshot"),
        "new_rules_fired": sorted({r for row in rows for r in row["new_rules"].split(";") if r}),
        "old_rules_fired": sorted({r for row in rows for r in row["old_rules"].split(";") if r}),
    }
    (out / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")

    md = ["# Old vs new AIV on aiv-airflow-bench", "",
          f"Generated {summary['generated_at']}. {len(rows)} branches. Base ref: `main` for both.", "",
          "## Labeled ground truth (synthetic fixtures + case/*)", "",
          "| build | n | correct | TP | FN | FP | TN | recall | precision |", "|---|---|---|---|---|---|---|---|---|"]
    for k in ("old", "new"):
        g = summary["labeled_ground_truth"][k]
        md.append(f"| {k} | {g['n']} | {g['correct']} | {g['TP']} | {g['FN']} | {g['FP']} | {g['TN']} | "
                  f"{g['recall']} | {g['precision']} |")
    md += ["", "## Real abandoned Airflow PRs (no hard ground truth; what fires)", "",
           "| cohort | n | old hard-fail | new hard-fail | old any signal | new any signal |", "|---|---|---|---|---|---|"]
    for k, v in summary["abandoned_real_prs"].items():
        md.append(f"| {k} | {v['n']} | {v['old_hard_fail']} | {v['new_hard_fail']} | {v['old_any_signal']} | {v['new_any_signal']} |")
    v = summary["snapshots"]
    md.append(f"| snapshot | {v['n']} | {v['old_hard_fail']} | {v['new_hard_fail']} | {v['old_any_signal']} | {v['new_any_signal']} |")
    md += ["", "## Per branch", "", "| branch | expected | old | new | old gates | new hard | new soft | new rules |",
           "|---|---|---|---|---|---|---|---|"]
    for r in rows:
        md.append(f"| {r['branch']} | {r['expected']} | {r['old_verdict']} | {r['new_verdict']} | "
                  f"{r['old_hard'] or '-'} | {r['new_hard'] or '-'} | {r['new_soft'] or '-'} | {r['new_rules'] or '-'} |")
    (out / "COMPARE.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    sys.exit(main())
