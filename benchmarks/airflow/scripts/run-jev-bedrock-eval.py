# Copyright 2026 Vaquar Khan
# SPDX-License-Identifier: Apache-2.0
"""Evaluate the AIV `jev` gate (model judgment) on Airflow PRs with Amazon Bedrock, alone and with AIV.

Steps (all local except the Bedrock calls made by the adapter):
1. Sample human Airflow PRs that have stored patches: every AI-flagged closed-unmerged PR (slop proxy),
   and seeded random samples of AI-flagged merged, non-AI merged and non-AI closed PRs.
2. Remove the AI-disclosure section from each description, so the model judges the change, not the label.
3. Run the real JevGate through JevCorpusRunner against the local jev-bedrock-adapter.
4. Report per question and combined: share of each group flagged at several P(yes) thresholds, and the
   union with the deterministic AIV signals (gate plus history) measured by measure-slop-signal-recall.py.

    AWS_PROFILE=<p> python integrations/jev-bedrock-adapter/jev_bedrock_adapter.py --port 8787 &
    python benchmarks/airflow/scripts/measure-slop-signal-recall.py --gate-rows <rows.json> --dump-hits <hits.json>
    python benchmarks/airflow/scripts/run-jev-bedrock-eval.py --hits <hits.json>
"""
import argparse
import base64
import glob
import json
import os
import random
import re
import subprocess
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ap = argparse.ArgumentParser()
ap.add_argument("--bench", default=str(ROOT.parent / "aiv-airflow-bench"))
ap.add_argument("--hits", required=True, help="per-PR deterministic hits from measure-slop-signal-recall.py --dump-hits")
ap.add_argument("--base-url", default="http://127.0.0.1:8787")
ap.add_argument("--threads", type=int, default=6)
ap.add_argument("--n-ai-merged", type=int, default=100)
ap.add_argument("--n-merged", type=int, default=100)
ap.add_argument("--n-closed", type=int, default=60)
ap.add_argument("--limit", type=int, default=0, help="smoke test: only this many PRs in total")
ap.add_argument("--seed", type=int, default=20261024)
ap.add_argument("--out-md", default=str(ROOT / "benchmarks/airflow/reports/jev-bedrock-eval-latest.md"))
ap.add_argument("--out-json", default=str(ROOT / "benchmarks/airflow/results/jev-bedrock-eval-latest.json"))
ARGS = ap.parse_args()
C = Path(ARGS.bench) / "corpus"
CHECKS = ROOT / "integrations" / "jev-bedrock-adapter" / "jev-slop-checks.json"

BOT = re.compile(r"(?i)(\[bot\]$|^github-actions|^dependabot|^renovate)")
MECHANICAL_TITLE = re.compile(r"(?i)^\s*(\[v\d+-\d+-test\]|\[v\d|sync v\d|prepare (providers )?release|bump the |update .*constraints|upgrade important ci)")
DISCLOSURE_LINE = re.compile(r"(?i)generative ai|generated-by|co-authored-by|^\s*-\s*\[[ xX]\]\s*yes|\b(claude|copilot|cursor|chatgpt|gpt-\d|gemini|codex|devin|windsurf|antigravity)\b")


def strip_disclosure(body):
    return "\n".join(l for l in re.sub(r"(?s)<!--.*?-->", "", body or "").split("\n") if not DISCLOSURE_LINE.search(l))


def b64(s):
    return base64.b64encode((s or "").encode("utf-8")).decode("ascii")


index = {}
for line in (C / "airflow-all" / "prs.jsonl").read_text(encoding="utf-8").split("\n"):
    if line.strip():
        r = json.loads(line)
        index[r["number"]] = r
patches = {}
for d in (C / "ai-year" / "prs", C / "e2e-proof" / "prs"):
    for f in sorted(d.glob("*.json")):
        pr = json.loads(f.read_text(encoding="utf-8"))
        if pr.get("files") and pr["number"] in index:
            patches.setdefault(pr["number"], pr)


def group(n):
    r = index[n]
    if BOT.search(r.get("user") or "") or MECHANICAL_TITLE.search(r.get("title") or ""):
        return None
    ai = r.get("label") in ("ai_disclosed", "ai_tool_mention", "ai_slop_discussion") or bool(r.get("generated_by"))
    if r.get("merged"):
        return "ai_merged" if ai else "merged"
    if r["state"] == "CLOSED":
        return "slop_proxy" if ai else "closed"
    return None


pools = {}
for n in sorted(patches):
    g = group(n)
    if g:
        pools.setdefault(g, []).append(n)
rnd = random.Random(ARGS.seed)
sample = {"slop_proxy": pools.get("slop_proxy", [])}
for g, k in (("ai_merged", ARGS.n_ai_merged), ("merged", ARGS.n_merged), ("closed", ARGS.n_closed)):
    pool = pools.get(g, [])
    sample[g] = sorted(rnd.sample(pool, min(k, len(pool))))
order = [(g, n) for g, ns in sample.items() for n in ns]
if ARGS.limit:
    order = order[:ARGS.limit]
print("sample:", {g: len(v) for g, v in sample.items()}, "running", len(order))


def unified(files):
    out = []
    for f in files:
        path = f.get("filename") or ""
        old = f.get("previous_filename") or path
        out.append(f"diff --git a/{old} b/{path}\n--- a/{old}\n+++ b/{path}\n")
        if f.get("patch"):
            out.append(f["patch"].rstrip("\n") + "\n")
    return "".join(out)


mod = ROOT / "aiv-plugin-jev" / "target"
m2 = Path.home() / ".m2" / "repository"
cp = [str(mod / "classes"), str(mod / "test-classes"), str(ROOT / "aiv-api" / "target" / "aiv-api-1.0.4.jar")]
cp += sorted(glob.glob(str(m2 / "org/slf4j/slf4j-api/*/slf4j-api-*.jar")))[-1:]
with tempfile.TemporaryDirectory() as tmp:
    inp, outp = Path(tmp) / "in.tsv", Path(tmp) / "out.tsv"
    rows = []
    for g, n in order:
        pr = patches[n]
        rows.append("\t".join([str(n), b64(pr.get("title")), b64(strip_disclosure(pr.get("body"))), b64(unified(pr["files"])),
                               b64("\n".join(x.get("filename") or "" for x in pr["files"]))]))
    inp.write_text("\n".join(rows) + "\n", encoding="utf-8")
    subprocess.run(["java", "-cp", os.pathsep.join(cp), "io.github.vaquarkhan.aiv.plugin.jev.JevCorpusRunner",
                    str(inp), str(outp), ARGS.base_url, str(ARGS.threads), str(CHECKS)], check=True)
    res = {}
    for line in outp.read_text(encoding="utf-8").splitlines():
        rid, outcome, probs, summary = line.split("\t", 3)
        res[int(rid)] = {"outcome": outcome, "summary": summary[:400],
                         "p": {k: float(v) for k, v in (x.split("=") for x in probs.split(",") if x)}}

try:
    stats = json.loads(urllib.request.urlopen(ARGS.base_url + "/stats", timeout=10).read())
except Exception:
    stats = {}

hits = {int(k): set(v) for k, v in json.loads(Path(ARGS.hits).read_text(encoding="utf-8")).items()}
recall_json = json.loads((ROOT / "benchmarks/airflow/results/slop-signal-recall-latest.json").read_text(encoding="utf-8"))
det_set = set(recall_json["greedy"]["gate+history@10%"]["signals"])
check_ids = list(json.loads(CHECKS.read_text(encoding="utf-8")))
groups = {g: [n for (gg, n) in order if gg == g and res.get(n, {}).get("outcome") != "skipped"] for g in sample}
skipped = [n for (_, n) in order if res.get(n, {}).get("outcome") == "skipped"]


def share(pred, g):
    ns = groups[g]
    return sum(1 for n in ns if pred(n)) / max(1, len(ns))


def jev_any(t, ids=None):
    return lambda n: any(res[n]["p"].get(q, 0.0) >= t for q in (ids or check_ids))


def det(n):
    return bool(hits.get(n, set()) & det_set)


GN = {"slop_proxy": "Slop proxy (AI, closed unmerged)", "ai_merged": "AI merged", "merged": "Non-AI merged",
      "closed": "Non-AI closed"}
summary = {"sample": {g: len(v) for g, v in groups.items()}, "skipped": len(skipped), "adapter": stats,
           "deterministic_signals": sorted(det_set), "thresholds": {}}
md = ["# JEV on Amazon Bedrock vs likely AI slop (Airflow, PRs with stored patches)", "",
      "Generated by `benchmarks/airflow/scripts/run-jev-bedrock-eval.py`. The real `jev` gate ran against "
      "`integrations/jev-bedrock-adapter` (one Bedrock Converse call per PR, temperature 0) with the six questions "
      "in `integrations/jev-bedrock-adapter/jev-slop-checks.json`. AI-disclosure lines were removed from "
      "descriptions so the model judges the change, not the label. Deterministic AIV = any of "
      + ", ".join(f"`{s}`" for s in sorted(det_set)) + " (the gate plus history set at a 10% merged budget).", "",
      f"Model: {stats.get('model', 'n/a')}; calls {stats.get('calls', 'n/a')}; input tokens {stats.get('input_tokens', 'n/a')}; "
      f"output tokens {stats.get('output_tokens', 'n/a')}; skipped PRs {len(skipped)}.", "",
      "| Group | PRs evaluated |", "|---|---:|"] + [f"| {GN[g]} | {len(v)} |" for g, v in groups.items()]
md += ["", "## Combined results by threshold", "",
       "Share of each group flagged. A good layer flags much more of the slop proxy than of the merged groups.", "",
       "| P(yes) threshold | Layer | Slop proxy | AI merged | Non-AI merged | Non-AI closed |", "|---|---|---:|---:|---:|---:|"]
for t in (0.5, 0.6, 0.7, 0.8, 0.9):
    layers = {"AIV deterministic": det, "JEV (any question)": jev_any(t),
              "AIV + JEV": (lambda f: (lambda n: det(n) or f(n)))(jev_any(t))}
    summary["thresholds"][t] = {}
    for name, pred in layers.items():
        row = {g: share(pred, g) for g in groups}
        summary["thresholds"][t][name] = row
        md.append(f"| {t:.1f} | {name} | " + " | ".join(f"{100 * row[g]:.1f}%" for g in groups) + " |")
md += ["", "## Each question at P(yes) 0.7", "", "| Question | Slop proxy | AI merged | Non-AI merged | Non-AI closed |",
       "|---|---:|---:|---:|---:|"]
summary["questions_at_0.7"] = {}
for q in check_ids:
    row = {g: share(jev_any(0.7, [q]), g) for g in groups}
    summary["questions_at_0.7"][q] = row
    md.append(f"| `{q}` | " + " | ".join(f"{100 * row[g]:.1f}%" for g in groups) + " |")
md += ["", "## Examples (slop proxy flagged by JEV at 0.8, first 8)", ""]
for n in [n for n in groups["slop_proxy"] if jev_any(0.8)(n)][:8]:
    top = sorted(res[n]["p"].items(), key=lambda x: -x[1])[:2]
    md.append(f"- [#{n}](https://github.com/apache/airflow/pull/{n}) {index[n]['title'][:80]} ("
              + ", ".join(f"{k} {v:.2f}" for k, v in top) + ")")
md += ["", "## Merged PRs flagged by JEV at 0.8 (false-flag examples, first 8)", ""]
for n in [n for g in ("merged", "ai_merged") for n in groups[g] if jev_any(0.8)(n)][:8]:
    top = sorted(res[n]["p"].items(), key=lambda x: -x[1])[:2]
    md.append(f"- [#{n}](https://github.com/apache/airflow/pull/{n}) {index[n]['title'][:80]} ("
              + ", ".join(f"{k} {v:.2f}" for k, v in top) + ")")
summary["per_pr"] = {str(n): {"group": g, **res.get(n, {})} for g, n in order}
Path(ARGS.out_json).parent.mkdir(parents=True, exist_ok=True)
Path(ARGS.out_json).write_text(json.dumps(summary, indent=1), encoding="utf-8")
Path(ARGS.out_md).parent.mkdir(parents=True, exist_ok=True)
Path(ARGS.out_md).write_text("\n".join(md) + "\n", encoding="utf-8")
print("\n".join(md))
