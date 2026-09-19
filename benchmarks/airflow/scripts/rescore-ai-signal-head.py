#!/usr/bin/env python3
"""Re-score AI-signal abandoned PRs with head materialize + full Airflow AIV config."""
from __future__ import annotations

import json
import os
import shutil
import tempfile
import time
from pathlib import Path

from importlib.machinery import SourceFileLoader

e = SourceFileLoader(
    "e2e",
    str(Path(__file__).resolve().parent / "run-e2e-benchmark.py"),
).load_module()

REPORTS = e.BENCH / "reports"
abd_path = REPORTS / "abandoned-labeled-latest.json"
abd = json.loads(abd_path.read_text(encoding="utf-8"))

os.environ["AIV_CENSUS_MATERIALIZE"] = "head"
os.environ.pop("AIV_CENSUS_AIV_CONFIG", None)  # use default benchmarks/airflow/.aiv

signal = [r for r in abd["cases"] if r.get("label") != "abandoned_clean"]
jar = e.find_jar()
work_root = Path(tempfile.mkdtemp(prefix="aiv-head-resignal-"))
print(f"Re-scoring {len(signal)} AI-signal PRs with head+full config…", flush=True)
try:
    for i, r in enumerate(signal, 1):
        n = r["pr"]
        print(f"[{i}/{len(signal)}] #{n} ({r['label']})…", flush=True)
        work = Path(tempfile.mkdtemp(prefix=f"h-{n}-", dir=work_root))
        try:
            base = e.materialize_pr_workspace("apache/airflow", n, work)
            out = work / "out.json"
            report = e.run_aiv(jar, work, base, out)
            blocking, advisory = [], []
            for g in report.get("gates") or []:
                if g.get("passed", True):
                    continue
                gid = g.get("id") or "?"
                if g.get("blocks_ci") is False:
                    advisory.append(gid)
                else:
                    blocking.append(gid)
            hard = report.get("_exit_code", 1) == 1 or bool(blocking)
            r["aiv_head_full"] = {
                "hard_fail": hard,
                "blocking_gates": blocking,
                "advisory_gates": advisory,
                "exit_code": report.get("_exit_code"),
            }
            # Relabel hard signal if head finds hard fail and not already objective/disclosed
            if hard and r["label"] in ("abandoned_clean", "ai_signal_advisory"):
                r["label"] = "ai_signal_hard"
            print(f"  -> hard={hard} block={blocking} adv={advisory}", flush=True)
        except Exception as ex:  # noqa: BLE001
            r["aiv_head_full"] = {"error": str(ex), "hard_fail": False}
            print(f"  ERROR {ex}", flush=True)
        finally:
            shutil.rmtree(work, ignore_errors=True)
        time.sleep(0.15)
finally:
    shutil.rmtree(work_root, ignore_errors=True)

# Refresh claims using head_full when present
rows = abd["cases"]
by_label = {}
for r in rows:
    by_label[r["label"]] = by_label.get(r["label"], 0) + 1
n = len(rows)
clean = by_label.get("abandoned_clean", 0)


def hard_of(r):
    h = r.get("aiv_head_full") or r.get("aiv") or {}
    return bool(h.get("hard_fail"))


hard = sum(1 for r in rows if hard_of(r))
disclosed = [r for r in rows if r["label"] == "ai_disclosed"]
disc_caught = [r for r in disclosed if hard_of(r)]
signal_rows = [r for r in rows if r["label"] != "abandoned_clean"]
signal_caught = [r for r in signal_rows if hard_of(r)]

claims = abd["claims"]
claims.update(
    {
        "by_label": by_label,
        "abandoned_clean_pct": round(100 * clean / n, 1) if n else None,
        "ai_signal_pct": round(100 * (n - clean) / n, 1) if n else None,
        "hard_fail_among_sample": hard,
        "hard_fail_pct_among_sample": round(100 * hard / n, 1) if n else None,
        "disclosed_n": len(disclosed),
        "disclosed_hard_caught": len(disc_caught),
        "disclosed_catch_rate": (len(disc_caught) / len(disclosed)) if disclosed else None,
        "ai_signal_n": len(signal_rows),
        "ai_signal_hard_identified": len(signal_caught),
        "ai_signal_identification_rate": (len(signal_caught) / len(signal_rows)) if signal_rows else None,
        "scoring_note": "AI-signal cohort re-scored with materialize=head + full benchmarks/airflow/.aiv config",
        "bullets": [
            f"Of {n} abandoned Airflow PRs sampled, {clean} ({100*clean/n:.1f}%) labeled abandoned_clean.",
            f"AI-signal labels: {n-clean} ({100*(n-clean)/n:.1f}%) — mostly ai_disclosed, not marker-style objective slop.",
            f"Objective markers in sample: {by_label.get('ai_slop_objective', 0)}.",
            f"After AIV (head+full) on sample: hard-fail {hard}/{n} ({100*hard/n:.1f}%).",
            f"Among ai_disclosed abandoned: hard-caught {len(disc_caught)}/{len(disclosed)} "
            f"({100*len(disc_caught)/len(disclosed) if disclosed else 0:.1f}%).",
            f"Among all AI-signal labels: identified (hard) {len(signal_caught)}/{len(signal_rows)} "
            f"({100*len(signal_caught)/len(signal_rows) if signal_rows else 0:.1f}%).",
        ],
        "statement": (
            f"Most abandoned PRs are not marker-style AI slop ({100*clean/n:.1f}% abandoned_clean; "
            f"0 objective markers in this sample). {n-clean} PRs carry AI-signal labels "
            f"(mostly disclosure). Pushing the same diffs through AIV (head+full config) hard-blocked "
            f"{hard}/{n} of the sample and {len(disc_caught)}/{len(disclosed)} confirmed-disclosed abandoned PRs."
        ),
    }
)

abd["claims"] = claims
abd_path.write_text(json.dumps(abd, indent=2) + "\n", encoding="utf-8")

# regenerate HTML via main module helpers
mod = SourceFileLoader(
    "abd",
    str(Path(__file__).resolve().parent / "benchmark-abandoned-labeled.py"),
).load_module()
mod.write_report(abd, REPORTS / "abandoned-labeled-latest.html")
mod.write_master_report(abd)
print(json.dumps(claims, indent=2))
print("Updated", abd_path)
