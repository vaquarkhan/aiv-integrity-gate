#!/usr/bin/env python3
"""Score selected cases from the downloaded aiv-airflow-bench with full-head materialization."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import tempfile
from datetime import datetime, timezone
from importlib.machinery import SourceFileLoader
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DEFAULT_CORPUS = ROOT.parent / "aiv-airflow-bench" / "corpus" / "e2e-proof" / "cases.json"
E2E = SourceFileLoader("aiv_e2e_selected", str(Path(__file__).with_name("run-e2e-benchmark.py"))).load_module()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--prs", type=int, nargs="+", default=[59957, 70842])
    parser.add_argument("--jar", type=Path, default=ROOT / "aiv-cli" / "target" / "aiv-cli-1.0.4.jar")
    parser.add_argument("--output", type=Path,
                        default=ROOT / "benchmarks" / "airflow" / "results" / "selected-real-head-latest.json")
    args = parser.parse_args()
    corpus = json.loads(args.corpus.read_text(encoding="utf-8"))
    selected = {case["pr"]: case for case in corpus.get("cases", []) if case.get("pr") in set(args.prs)}
    missing = sorted(set(args.prs) - set(selected))
    if missing:
        raise SystemExit(f"PRs missing from corpus: {missing}")

    os.environ["AIV_CENSUS_MATERIALIZE"] = "head"
    jar = args.jar.resolve()
    if not jar.is_file():
        raise SystemExit(f"JAR not found: {jar}")
    rows = []
    for pr in args.prs:
        case = E2E.normalize_case(selected[pr])
        work = Path(tempfile.mkdtemp(prefix=f"aiv-airflow-{pr}-"))
        try:
            base = E2E.materialize_pr_workspace(case["repo"], pr, work)
            report_path = work / "aiv-report.json"
            report = E2E.run_aiv(jar, work, base, report_path)
            outcome = E2E.classify_outcome(case, report)
            rows.append({
                "pr": pr,
                "url": case.get("url"),
                "label": case.get("label"),
                "outcome": outcome,
                "gates": report.get("gates", []),
                "materialization": "changed-file head reconstruction with patch fallback",
                "repository_complete": False,
            })
            print(f"PR {pr}: {outcome['verdict']} hard={outcome['hard_fail']}", flush=True)
        finally:
            shutil.rmtree(work, ignore_errors=True)

    errors = [r for r in rows if r["outcome"]["verdict"] == "ERROR"]
    positives = [r for r in rows if (r.get("label") or {}).get("expect_hard_fail")]
    controls = [r for r in rows if not (r.get("label") or {}).get("expect_hard_fail")]
    tp = sum(r["outcome"]["verdict"] == "TP" for r in positives)
    fp = sum(r["outcome"]["verdict"] == "FP" for r in controls)
    payload = {
        "schema_version": 1,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source_corpus": E2E.portable_path(args.corpus),
        "materialization": "changed-file head reconstruction with patch fallback; not a complete repository checkout",
        "aiv_jar": E2E.portable_path(jar),
        "rows": rows,
        "metrics": {
            "objective_positives": len(positives),
            "true_positives": tp,
            "objective_recall": tp / len(positives) if positives else None,
            "controls": len(controls),
            "hard_false_positives": fp,
            "errors": len(errors),
        },
        "claim_limit": "Selected-case validation only; not a representative estimate for all Airflow PRs.",
    }
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload["metrics"], indent=2))
    return 0 if not errors and tp == len(positives) and fp == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
