#!/usr/bin/env python3
"""Score the small set of Airflow PRs with confirmed ChatGPT/AI disclosure checkboxes."""
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

# Confirmed from body inspection (AIP-120 window ChatGPT search + known)
PRS = [73124, 70250, 70842, 72453, 72372]
REPO = "apache/airflow"
os.environ["AIV_CENSUS_MATERIALIZE"] = "head"

jar = e.find_jar()
work_root = Path(tempfile.mkdtemp(prefix="aiv-chatgpt-true-"))
rows = []
try:
    for n in PRS:
        meta = e.fetch_pr_meta(REPO, n)
        body = e.gh_api(f"repos/{REPO}/pulls/{n}").get("body") or ""
        markers, _ = e.scan_pr_markers(REPO, n)
        work = Path(tempfile.mkdtemp(prefix=f"pr-{n}-", dir=work_root))
        print(f"Scoring #{n} merged={meta.get('merged')}…", flush=True)
        try:
            base = e.materialize_pr_workspace(REPO, n, work)
            out = work / "out.json"
            report = e.run_aiv(jar, work, base, out)
            exit_code = report.get("_exit_code", 1)
            blocking = []
            advisory = []
            for g in report.get("gates") or []:
                if g.get("passed", True):
                    continue
                gid = g.get("id") or "?"
                if g.get("blocks_ci") is False:
                    advisory.append(gid)
                else:
                    blocking.append(gid)
            hard = exit_code == 1 or bool(blocking)
            row = {
                "pr": n,
                "url": f"https://github.com/{REPO}/pull/{n}",
                "title": meta.get("title"),
                "merged": bool(meta.get("merged")),
                "markers_in_patch": markers,
                "aiv": {
                    "hard_fail": hard,
                    "blocking_gates": blocking,
                    "advisory_gates": advisory,
                    "exit_code": exit_code,
                },
            }
            rows.append(row)
            print(f"  -> hard={hard} block={blocking} adv={advisory} markers={markers}", flush=True)
        except Exception as ex:  # noqa: BLE001
            rows.append({"pr": n, "error": str(ex), "merged": bool(meta.get("merged"))})
            print(f"  ERROR {ex}", flush=True)
        time.sleep(0.2)
finally:
    shutil.rmtree(work_root, ignore_errors=True)

closed = [r for r in rows if not r.get("merged") and "error" not in r]
merged = [r for r in rows if r.get("merged") and "error" not in r]
hard_c = [r for r in closed if (r.get("aiv") or {}).get("hard_fail")]
hard_m = [r for r in merged if (r.get("aiv") or {}).get("hard_fail")]
claims = {
    "tag": "VERIFIED",
    "cohort": "confirmed_chatgpt_disclosure_bodies",
    "n": len(rows),
    "closed_unmerged_scored": len(closed),
    "closed_unmerged_hard_blocked": len(hard_c),
    "closed_unmerged_catch_rate": (len(hard_c) / len(closed)) if closed else None,
    "merged_scored": len(merged),
    "merged_hard_blocked": len(hard_m),
    "merged_fp_if_treat_merged_as_good": (len(hard_m) / len(merged)) if merged else None,
    "statement": (
        f"On {len(closed)} closed-unmerged Airflow PRs with confirmed ChatGPT disclosure, "
        f"AIV hard-blocked {len(hard_c)} ({(100*len(hard_c)/len(closed)) if closed else 0:.0f}%). "
        f"On {len(merged)} merged ChatGPT-disclosed PRs, hard-blocked {len(hard_m)} "
        f"(FP rate if merged=good: {(100*len(hard_m)/len(merged)) if merged else 0:.0f}%)."
    ),
    "not_claimed": [
        "That all AI-assisted Airflow PRs should be blocked",
        "That ChatGPT search totals equal AI-slop volume",
        "% of 597 closed-unmerged that are AI slop",
    ],
}
out = {
    "claims": claims,
    "cases": rows,
    "volume_context": {
        "closed_unmerged_window": 597,
        "search_chatgpt_closed": 5,
        "search_generated_by_noisy_template": 1910,
    },
}
path = e.BENCH / "reports" / "airflow-chatgpt-true-latest.json"
path.write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
print(json.dumps(claims, indent=2))
print("Wrote", path)
