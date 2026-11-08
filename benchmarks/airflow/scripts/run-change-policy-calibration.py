# Copyright 2026 Vaquar Khan
# SPDX-License-Identifier: Apache-2.0
"""Calibrate the change-policy gate on every stored PR patch in aiv-airflow-bench.

Runs the real Java gate (aiv-plugin-change-policy, through CorpusCalibrationRunner) on each PR, then
reports per policy: flag rate on merged and on closed-unmerged PRs (Wilson 95% interval on merged),
lift (closed rate / merged rate), merge rate when flagged, and example PRs for manual review.

    mvn -o -pl aiv-plugin-change-policy -am install      # builds classes and test classes
    python benchmarks/airflow/scripts/run-change-policy-calibration.py [--bench ../aiv-airflow-bench]

Writes benchmarks/airflow/results/change-policy-calibration-latest.json and
benchmarks/airflow/reports/change-policy-calibration-latest.md. Git history facts are unavailable
without checkouts, and the corpus has no author_association, so behind-base and
ci-change-by-non-collaborator are reported as skipped.
"""
import argparse
import base64
import glob
import json
import math
import os
import re
import subprocess
import tempfile
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ap = argparse.ArgumentParser()
ap.add_argument("--bench", default=str(ROOT.parent / "aiv-airflow-bench"))
ap.add_argument("--ceiling", type=float, default=0.02, help="merged-PR flag-rate ceiling for promotion")
ap.add_argument("--out-json", default=str(ROOT / "benchmarks/airflow/results/change-policy-calibration-latest.json"))
ap.add_argument("--out-md", default=str(ROOT / "benchmarks/airflow/reports/change-policy-calibration-latest.md"))
ap.add_argument("--rows", default=None, help="also write per-PR rows (fired policies and facts) to this JSON file")
ARGS = ap.parse_args()

C = Path(ARGS.bench) / "corpus"
SOURCES = {"airflow-ai-year": C / "ai-year" / "prs", "airflow-e2e": C / "e2e-proof" / "prs"}
for p in ["cosmic", "curl", "flink", "ghostty", "godot", "kafka"]:
    SOURCES[p] = C / "benchmarks" / p / "prs"


def outcome(pr):
    if pr.get("merged"):
        return "merged"
    return "closed" if (pr.get("state") or "").lower() == "closed" else "open"


def disclosed(pr):
    d = pr.get("disclosure") or {}
    fl = pr.get("flag") or {}
    return bool(d.get("disclosed") or d.get("generated_by") or fl.get("ai_related"))


BOT = re.compile(r"(?i)(\[bot\]$|^github-actions|^dependabot|^renovate)")
MECHANICAL_TITLE = re.compile(r"(?i)^\s*(\[v\d+-\d+-test\]|\[v\d|sync v\d|prepare (providers )?release|bump the |update .*constraints|upgrade important ci)")


def mechanical(pr):
    """Bot, backport and release PRs, reported separately because maintainers handle them differently."""
    user = pr.get("user")
    user = (user or {}).get("login") if isinstance(user, dict) else user
    return bool(BOT.search(user or "") or MECHANICAL_TITLE.search(pr.get("title") or ""))


def b64(s):
    return base64.b64encode((s or "").encode("utf-8")).decode("ascii")


def unified(files):
    out = []
    for f in files:
        path = f.get("filename") or ""
        old = f.get("previous_filename") or path
        out.append(f"diff --git a/{old} b/{path}\n--- a/{old}\n+++ b/{path}\n")
        if f.get("patch"):
            out.append(f["patch"].rstrip("\n") + "\n")
    return "".join(out)


def wilson(k, n, z=1.96):
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    d = 1 + z * z / n
    c = p + z * z / (2 * n)
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return ((c - h) / d, (c + h) / d)


def classpath():
    mod = ROOT / "aiv-plugin-change-policy" / "target"
    m2 = Path.home() / ".m2" / "repository"
    jars = [str(mod / "classes"), str(mod / "test-classes"), str(ROOT / "aiv-api" / "target" / "aiv-api-1.0.4.jar")]
    for pattern in ["org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar", "org/slf4j/slf4j-api/*/slf4j-api-*.jar"]:
        found = sorted(glob.glob(str(m2 / pattern)))
        if found:
            jars.append(found[-1])
    return os.pathsep.join(jars)


rows, seen = [], set()
for src, d in SOURCES.items():
    for f in sorted(d.glob("*.json")):
        try:
            pr = json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            continue
        repo = "airflow" if src.startswith("airflow") else src
        key = (repo, pr.get("number"))
        if key in seen or not pr.get("files"):
            continue
        seen.add(key)
        files = pr["files"]
        listing = "\n".join(f"{x.get('status') or 'modified'}\t{x.get('filename')}\t{x.get('previous_filename') or ''}" for x in files)
        rows.append({"id": f"{repo}#{pr.get('number')}", "repo": repo, "outcome": outcome(pr), "ai": disclosed(pr),
                     "title": pr.get("title") or "", "pack": "airflow" if repo == "airflow" else "oss-maintainer",
                     "tier": "patch", "mechanical": mechanical(pr),
                     "tsv": "\t".join([f"{repo}#{pr.get('number')}", "airflow" if repo == "airflow" else "oss-maintainer",
                                       b64(pr.get("title")), b64(pr.get("body")), b64(unified(files)), b64(listing),
                                       str(pr.get("additions") or 0), str(pr.get("deletions") or 0)])})

# Index tier: every Airflow PR in the full index, without patches. Only file count, total lines, title and
# body are real, so only policies over those facts (mega-diff, small-fix-huge-diff) are reported for it.
INDEX_POLICIES = ["mega-diff", "small-fix-huge-diff"]
for line in (C / "airflow-all" / "prs.jsonl").read_text(encoding="utf-8").split("\n"):
    if not line.strip():
        continue
    r = json.loads(line)
    n = r["number"]
    listing = "\n".join(f"modified\tindex-only/file-{i}" for i in range(r.get("changed_files") or 0))
    rows.append({"id": f"airflow-index#{n}", "repo": "airflow-index", "pr": n,
                 "outcome": "merged" if r.get("merged") else ("closed" if (r.get("state") or "").upper() == "CLOSED" else "open"),
                 "ai": r.get("label") in ("ai_disclosed", "ai_tool_mention", "ai_slop_discussion") or bool(r.get("generated_by")),
                 "title": r.get("title") or "", "pack": "airflow", "tier": "index", "mechanical": mechanical(r),
                 "tsv": "\t".join([f"airflow-index#{n}", "airflow", b64(r.get("title")), b64(r.get("body")), b64(""),
                                   b64(listing), str(r.get("additions") or 0), str(r.get("deletions") or 0)])})

with tempfile.TemporaryDirectory() as tmp:
    inp, outp = Path(tmp) / "in.tsv", Path(tmp) / "out.tsv"
    inp.write_text("\n".join(r["tsv"] for r in rows) + "\n", encoding="utf-8")
    subprocess.run(["java", "-cp", classpath(), "io.github.vaquarkhan.aiv.plugin.changepolicy.CorpusCalibrationRunner",
                    str(inp), str(outp)], check=True)
    results = {}
    for line in outp.read_text(encoding="utf-8").splitlines():
        rid, gate, fired, skipped, facts = line.split("\t", 4)
        results[rid] = {"gate": gate, "fired": [x for x in fired.split(",") if x], "skipped": skipped,
                        "facts": json.loads(facts)}
for r in rows:
    r.update(results[r["id"]])
    del r["tsv"]

ALL_POLICIES = ["mega-diff", "over-budget", "small-fix-huge-diff", "small-intent-large-diff", "untested-source-change",
                "large-new-code-unit", "infra-with-source", "ci-change-by-non-collaborator", "lockfile-change",
                "formatting-churn", "description-claims-mismatch", "template-compliance", "disclosure-consistency",
                "behind-base", "commit-hygiene"]


def evaluate(pool, policies):
    decided = [r for r in pool if r["outcome"] in ("merged", "closed")]
    merged = [r for r in decided if r["outcome"] == "merged"]
    closed = [r for r in decided if r["outcome"] == "closed"]
    skipped = Counter()
    for r in pool:
        for part in r["skipped"].split("; "):
            if part:
                skipped[part.split(" (")[0]] += 1
    table = []
    for p in policies:
        km = sum(p in r["fired"] for r in merged)
        kc = sum(p in r["fired"] for r in closed)
        ka = sum(p in r["fired"] for r in pool if r["ai"])
        kn = sum(p in r["fired"] for r in pool if not r["ai"])
        pm, pc = km / max(1, len(merged)), kc / max(1, len(closed))
        lo, hi = (max(0.0, x) for x in wilson(km, len(merged)))
        flagged = km + kc
        table.append({
            "policy": p, "merged": [km, len(merged)], "closed": [kc, len(closed)],
            "merged_rate": pm, "merged_rate_ci95": [lo, hi], "closed_rate": pc,
            "lift": (pc / pm) if pm else None, "merge_rate_when_flagged": (km / flagged) if flagged else None,
            "ai": [ka, sum(r["ai"] for r in pool)], "not_ai": [kn, sum(not r["ai"] for r in pool)],
            "skipped_prs": skipped.get(p, 0),
            "promotable": bool(flagged and pm <= ARGS.ceiling and pc > pm),
            "examples": [f"{r['id']} {r['outcome']}: {r['title'][:80]}" for r in decided if p in r["fired"]][:8],
        })
    fired = lambda r: any(p in r["fired"] for p in policies)
    return {"prs": len(pool), "merged": len(merged), "closed": len(closed),
            "baseline_merge_rate": len(merged) / max(1, len(decided)),
            "any_policy": {"merged": [sum(fired(r) for r in merged), len(merged)],
                           "closed": [sum(fired(r) for r in closed), len(closed)]},
            "blocking": {"merged": [sum(r["gate"] == "fail" for r in merged), len(merged)],
                         "closed": [sum(r["gate"] == "fail" for r in closed), len(closed)]},
            "policies": table}


patch = [r for r in rows if r["tier"] == "patch"]
index = [r for r in rows if r["tier"] == "index"]
views = {
    "patch-all": (patch, ALL_POLICIES), "patch-human": ([r for r in patch if not r["mechanical"]], ALL_POLICIES),
    "index-all": (index, INDEX_POLICIES), "index-human": ([r for r in index if not r["mechanical"]], INDEX_POLICIES),
}
summary = {"ceiling": ARGS.ceiling, "by_repo": Counter(r["repo"] for r in rows),
           "views": {k: evaluate(pool, pols) for k, (pool, pols) in views.items()}}
Path(ARGS.out_json).parent.mkdir(parents=True, exist_ok=True)
Path(ARGS.out_json).write_text(json.dumps(summary, indent=1, default=dict), encoding="utf-8")
if ARGS.rows:
    Path(ARGS.rows).write_text(json.dumps(rows), encoding="utf-8")


def pct(x):
    return "n/a" if x is None else f"{100 * x:.1f}%"


def frac(pair):
    return f"{pair[0]} / {pair[1]} ({pct(pair[0] / max(1, pair[1]))})"


VIEW_TITLES = {
    "patch-all": "Stored patches, all PRs (Airflow with the `airflow` pack, six other projects with `oss-maintainer`)",
    "patch-human": "Stored patches, excluding bot, backport and release PRs",
    "index-all": "Full Airflow index (15,575 PRs, size and title facts only)",
    "index-human": "Full Airflow index, excluding bot, backport and release PRs",
}
md = ["# Change-policy calibration", "",
      "Generated by `benchmarks/airflow/scripts/run-change-policy-calibration.py` with the real gate "
      "(`aiv-plugin-change-policy`). Git history and author association are not in the corpus, so policies that "
      "need them are skipped. The index tier has no patches, so only `mega-diff` and `small-fix-huge-diff` are "
      "reported for it.", "",
      f"Promotion test: merged flag rate at most {pct(ARGS.ceiling)} and more common in closed PRs than merged ones.", ""]
for key, title in VIEW_TITLES.items():
    v = summary["views"][key]
    md += [f"## {title}", "",
           f"PRs: {v['prs']}; decided {v['merged']} merged, {v['closed']} closed; baseline merge rate "
           f"{pct(v['baseline_merge_rate'])}. Any policy fired: merged {frac(v['any_policy']['merged'])}, closed "
           f"{frac(v['any_policy']['closed'])}. Gate would block: merged {frac(v['blocking']['merged'])}, closed "
           f"{frac(v['blocking']['closed'])}.", "",
           "| Policy | Merged flagged (95% CI) | Closed flagged | Lift | Merge rate when flagged | AI / not AI | Skipped PRs | Promotable |",
           "|---|---|---|---:|---:|---|---:|---|"]
    for t in v["policies"]:
        lo, hi = t["merged_rate_ci95"]
        lift = "n/a" if t["lift"] is None else f"{t['lift']:.2f}"
        md.append(f"| `{t['policy']}` | {t['merged'][0]} ({pct(t['merged_rate'])}, {pct(lo)} to {pct(hi)}) | "
                  f"{t['closed'][0]} ({pct(t['closed_rate'])}) | {lift} | {pct(t['merge_rate_when_flagged'])} | "
                  f"{pct(t['ai'][0] / max(1, t['ai'][1]))} / {pct(t['not_ai'][0] / max(1, t['not_ai'][1]))} | "
                  f"{t['skipped_prs']} | {'yes' if t['promotable'] else 'no'} |")
    md.append("")
md += ["## Examples (first decided PRs per policy, full views, for manual review)", ""]
for key in ("patch-all", "index-all"):
    for t in summary["views"][key]["policies"]:
        if t["examples"]:
            md.append(f"- {key} `{t['policy']}`: " + "; ".join(t["examples"][:4]))
Path(ARGS.out_md).parent.mkdir(parents=True, exist_ok=True)
Path(ARGS.out_md).write_text("\n".join(md) + "\n", encoding="utf-8")
print("\n".join(md))
