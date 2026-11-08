# Copyright 2026 Vaquar Khan
# SPDX-License-Identifier: Apache-2.0
"""How much likely AI slop can deterministic signals catch on the Airflow corpus, and at what cost?

There is no human-labeled AI slop set in the corpus, so this script uses an explicit proxy and reports
recall against it next to the false-flag rate on merged PRs:

* slop proxy: AI-flagged (disclosure or tool mention) Airflow PRs that were closed unmerged, excluding bot,
  backport and release PRs. Research on agentic PRs (arXiv 2605.22534) finds only about 36% of rejected
  agent PRs are clear agent failures, so this proxy over-counts slop; recall on it is a lower bound.
* controls: AI-flagged merged PRs (good AI-assisted work) and all merged human PRs.

Signals are deterministic and computable at PR time from the PR itself, from the change-policy gate, or
from repository history GitHub already exposes (other PRs, the author's earlier PRs). Each signal is
tagged with where it would run: `gate` (per PR, AIV), `history` (needs the project's PR history, for
example Magpie or a GitHub query from the gate).

    python benchmarks/airflow/scripts/run-change-policy-calibration.py --rows <rows.json>
    python benchmarks/airflow/scripts/measure-slop-signal-recall.py --gate-rows <rows.json>

Writes benchmarks/airflow/reports/slop-signal-recall-latest.md and results/slop-signal-recall-latest.json.
"""
import argparse
import json
import re
from collections import defaultdict
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ap = argparse.ArgumentParser()
ap.add_argument("--bench", default=str(ROOT.parent / "aiv-airflow-bench"))
ap.add_argument("--gate-rows", required=True, help="per-PR rows written by run-change-policy-calibration.py --rows")
ap.add_argument("--budgets", default="0.02,0.05,0.10", help="merged-PR false-flag budgets for the greedy union")
ap.add_argument("--out-md", default=str(ROOT / "benchmarks/airflow/reports/slop-signal-recall-latest.md"))
ap.add_argument("--out-json", default=str(ROOT / "benchmarks/airflow/results/slop-signal-recall-latest.json"))
ap.add_argument("--dump-hits", default=None, help="write {pr: [signals]} for every human PR (used by run-jev-bedrock-eval.py)")
ARGS = ap.parse_args()
C = Path(ARGS.bench) / "corpus"

BOT = re.compile(r"(?i)(\[bot\]$|^github-actions|^dependabot|^renovate)")
MECHANICAL_TITLE = re.compile(r"(?i)^\s*(\[v\d+-\d+-test\]|\[v\d|sync v\d|prepare (providers )?release|bump the |update .*constraints|upgrade important ci)")
SMALL = re.compile(r"(?i)\b(typos?|spelling|grammar|docs?|documentation|readme|fix(es|ed)?|bug|bump|minor|small|tweak|nit)\b")
CONVENTIONAL = re.compile(r"^(feat|fix|chore|docs|refactor|test|tests|ci|build|perf|style)(\([^)]*\))?!?:\s", re.I)
ISSUE_REF = re.compile(r"(?i)\b(?:closes|fixes|fix|resolves|related)\s*:?\s*#(\d{3,6})\b|github\.com/apache/airflow/issues/(\d{3,6})")
TEMPLATE_END = re.compile(r"(?m)^\s*(\*\*\^ Add meaningful description above\*\*|#{2,6} Was generative AI tooling)")
TEMPLATE_LINE = re.compile(r"(?i)^\s*(\*\s*)?(closes|related):\s*#ISSUE\s*$|^\s*In case of an existing issue|^\s*Thank you for contributing|^\s*-\s*\[\s?\]\s*Yes")
AGENT_BOILERPLATE = re.compile(r"(?m)^#{1,3}\s*(Summary|Test plan)\s*$|Generated with \[?Claude Code|^Co-Authored-By: Claude", re.I)
WORD = re.compile(r"[A-Za-z][A-Za-z'_-]+")


def own_description(body):
    """The author's own text: before the template footer, without HTML comments and template lines."""
    b = re.sub(r"(?s)<!--.*?-->", "", body or "")
    m = TEMPLATE_END.search(b)
    if m:
        b = b[:m.start()]
    return "\n".join(l for l in b.split("\n") if not TEMPLATE_LINE.search(l))


def ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


index = [json.loads(l) for l in (C / "airflow-all" / "prs.jsonl").read_text(encoding="utf-8").split("\n") if l.strip()]
gate = {}
for r in json.loads(Path(ARGS.gate_rows).read_text(encoding="utf-8")):
    key = r.get("pr") if r["tier"] == "index" else (int(r["id"].split("#")[1]) if r["id"].startswith("airflow#") else None)
    if key is not None:
        gate.setdefault(key, set()).update(r["fired"])
patched = {int(r["id"].split("#")[1]) for r in json.loads(Path(ARGS.gate_rows).read_text(encoding="utf-8"))
           if r["tier"] == "patch" and r["id"].startswith("airflow#")}

# history views (what GitHub exposes at PR-open time)
by_author = defaultdict(list)
by_issue = defaultdict(list)
for r in index:
    r["_t"] = ts(r["created_at"])
    by_author[r.get("user") or ""].append(r)
    for m in ISSUE_REF.finditer(r.get("body") or ""):
        by_issue[int(m.group(1) or m.group(2))].append(r)
for lst in by_author.values():
    lst.sort(key=lambda x: x["_t"])

SIGNALS = {}


def signal(name, where, desc):
    def deco(fn):
        SIGNALS[name] = (where, desc, fn)
        return fn
    return deco


@signal("gate:mega-diff", "gate", "change-policy mega-diff (1,000+ files)")
def _(r): return "mega-diff" in gate.get(r["number"], ())
@signal("gate:small-fix-huge-diff", "gate", "change-policy small-fix-huge-diff")
def _(r): return "small-fix-huge-diff" in gate.get(r["number"], ())
@signal("gate:any-patch-policy", "gate", "any change-policy patch-level policy (patch tier only)")
def _(r): return bool(gate.get(r["number"], set()) - {"mega-diff", "small-fix-huge-diff"})
@signal("text:empty-own-description", "gate", "author wrote fewer than 15 words of their own above the template")
def _(r): return len(WORD.findall(own_description(r.get("body")))) < 15
@signal("text:unfilled-issue-placeholder", "gate", "template line `closes: #ISSUE` left unfilled")
def _(r): return bool(re.search(r"(?im)^\s*(\*\s*)?(closes|related):\s*#ISSUE\s*$", re.sub(r"(?s)<!--.*?-->", "", r.get("body") or "")))
@signal("text:very-long-description", "gate", "author's own description over 600 words")
def _(r): return len(WORD.findall(own_description(r.get("body")))) > 600
@signal("text:agent-boilerplate", "gate", "agent-default sections (## Summary / ## Test plan) or Claude Code footer")
def _(r): return bool(AGENT_BOILERPLATE.search(r.get("body") or ""))
@signal("text:conventional-title", "gate", "conventional-commit title prefix (feat:, fix:)")
def _(r): return bool(CONVENTIONAL.match(r.get("title") or ""))
@signal("text:fix-without-issue", "gate", "small-change title with no linked issue")
def _(r): return bool(SMALL.search(r.get("title") or "")) and not ISSUE_REF.search(r.get("body") or "")


@signal("history:duplicate-issue-pr", "history", "another PR already references the same issue (opened earlier)")
def _(r):
    for m in ISSUE_REF.finditer(r.get("body") or ""):
        n = int(m.group(1) or m.group(2))
        if any(o["number"] != r["number"] and o["_t"] < r["_t"] for o in by_issue.get(n, ())):
            return True
    return False


def earlier_same_issue(r):
    for m in ISSUE_REF.finditer(r.get("body") or ""):
        for o in by_issue.get(int(m.group(1) or m.group(2)), ()):
            if o["number"] != r["number"] and o["_t"] < r["_t"]:
                yield o


@signal("history:issue-already-fixed", "history", "an earlier PR for the same issue was merged before this PR was opened")
def _(r):
    return any(o.get("merged") and o.get("merged_at") and ts(o["merged_at"]) < r["_t"] for o in earlier_same_issue(r))


@signal("history:competing-open-pr", "history", "an earlier PR for the same issue was still open when this PR was opened")
def _(r):
    return any(not o.get("closed_at") or ts(o["closed_at"]) > r["_t"] for o in earlier_same_issue(r))


@signal("history:newcomer-burst", "history", "author's first PR in the window is under 30 days old and they opened 3+ other PRs in the last 7 days")
def _(r):
    mine = by_author[r.get("user") or ""]
    lo = r["_t"] - timedelta(days=7)
    return bool(mine) and r["_t"] - mine[0]["_t"] < timedelta(days=30) and sum(1 for o in mine if lo <= o["_t"] < r["_t"]) >= 3


@signal("history:no-merge-yet-and-burst", "history", "author has no merged PR yet and opened 3+ other PRs in the last 7 days")
def _(r):
    mine = by_author[r.get("user") or ""]
    lo = r["_t"] - timedelta(days=7)
    merged_before = any(o.get("merged_at") and ts(o["merged_at"]) < r["_t"] for o in mine)
    return not merged_before and sum(1 for o in mine if lo <= o["_t"] < r["_t"]) >= 3


@signal("history:author-burst", "history", "author opened 5+ other PRs in the 7 days before this one")
def _(r):
    lo = r["_t"] - timedelta(days=7)
    return sum(1 for o in by_author[r.get("user") or ""] if lo <= o["_t"] < r["_t"]) >= 5


@signal("history:first-pr-in-window", "history", "author has no earlier PR in the corpus window")
def _(r): return not any(o["_t"] < r["_t"] for o in by_author[r.get("user") or ""])


@signal("history:poor-track-record", "history", "author has 3+ earlier decided PRs and under 30% were merged")
def _(r):
    prior = [o for o in by_author[r.get("user") or ""] if o["_t"] < r["_t"] and (o.get("merged") or o.get("state") == "CLOSED")]
    prior = [o for o in prior if (o.get("closed_at") or "") and ts(o["closed_at"]) < r["_t"]]
    return len(prior) >= 3 and sum(bool(o.get("merged")) for o in prior) / len(prior) < 0.30


def is_ai(r):
    return r.get("label") in ("ai_disclosed", "ai_tool_mention", "ai_slop_discussion") or bool(r.get("generated_by"))


human = [r for r in index if not (BOT.search(r.get("user") or "") or MECHANICAL_TITLE.search(r.get("title") or ""))]
slop = [r for r in human if is_ai(r) and r["state"] == "CLOSED" and not r.get("merged")]
ai_merged = [r for r in human if is_ai(r) and r.get("merged")]
human_merged = [r for r in human if r.get("merged")]
non_ai_closed = [r for r in human if not is_ai(r) and r["state"] == "CLOSED" and not r.get("merged")]
groups = {"slop proxy (AI, closed unmerged)": slop, "AI merged": ai_merged, "all merged": human_merged,
          "non-AI closed": non_ai_closed}

hits = {name: {r["number"] for r in human if fn(r)} for name, (_, _, fn) in SIGNALS.items()}
if ARGS.dump_hits:
    per_pr = {r["number"]: [] for r in human}
    for name, prs in hits.items():
        for n in prs:
            per_pr[n].append(name)
    Path(ARGS.dump_hits).write_text(json.dumps(per_pr), encoding="utf-8")


def rate(name_or_set, group):
    s = hits[name_or_set] if isinstance(name_or_set, str) else name_or_set
    return sum(r["number"] in s for r in group) / max(1, len(group))


table = []
for name, (where, desc, _) in SIGNALS.items():
    table.append({"signal": name, "where": where, "desc": desc,
                  **{g: rate(name, rows) for g, rows in groups.items()}})

# greedy union under a merged false-flag budget: add the signal with the best slop gain per merged cost
greedy = {}
for budget in [float(x) for x in ARGS.budgets.split(",")]:
    for scope in ("gate", "gate+history"):
        chosen, cur = [], set()
        allowed = [n for n, (w, _, _) in SIGNALS.items() if scope == "gate+history" or w == "gate"]
        while True:
            best, best_score = None, 0.0
            for n in allowed:
                if n in chosen:
                    continue
                cand = cur | hits[n]
                fp = rate(cand, human_merged)
                if fp > budget:
                    continue
                gain = rate(cand, slop) - rate(cur, slop)
                cost = fp - rate(cur, human_merged)
                score = gain / (cost + 0.002)
                if gain > 0 and score > best_score:
                    best, best_score = n, score
            if best is None:
                break
            chosen.append(best)
            cur |= hits[best]
        greedy[f"{scope}@{budget:.0%}"] = {
            "signals": chosen, "slop_recall": rate(cur, slop), "merged_flag_rate": rate(cur, human_merged),
            "ai_merged_flag_rate": rate(cur, ai_merged), "non_ai_closed_rate": rate(cur, non_ai_closed)}

# patch-tier view for the gate's patch policies (only PRs with stored patches)
p_slop = [r for r in slop if r["number"] in patched]
p_merged = [r for r in human_merged if r["number"] in patched]

out = {"groups": {g: len(rows) for g, rows in groups.items()}, "signals": table, "greedy": greedy,
       "patch_tier": {"slop": len(p_slop), "merged": len(p_merged),
                      "any_patch_policy_recall": rate("gate:any-patch-policy", p_slop),
                      "any_patch_policy_merged": rate("gate:any-patch-policy", p_merged)}}
Path(ARGS.out_json).parent.mkdir(parents=True, exist_ok=True)
Path(ARGS.out_json).write_text(json.dumps(out, indent=1), encoding="utf-8")


def pct(x):
    return f"{100 * x:.1f}%"


md = ["# Deterministic signals vs likely AI slop (Airflow corpus)", "",
      "Generated by `benchmarks/airflow/scripts/measure-slop-signal-recall.py`. Proxy for AI slop: AI-flagged "
      "Airflow PRs closed unmerged, excluding bot, backport and release PRs. The proxy over-counts slop "
      "(many rejections are duplicates, staleness or workflow), so recall on it is a lower bound and the "
      "merged false-flag rate is the cost to watch.", "",
      "| Group | PRs |", "|---|---:|"] + [f"| {g} | {n} |" for g, n in out["groups"].items()]
md += ["", "## Each signal", "",
       "| Signal | Runs in | Meaning | Slop proxy caught | AI merged flagged | All merged flagged | Non-AI closed flagged |",
       "|---|---|---|---:|---:|---:|---:|"]
for t in sorted(table, key=lambda t: -t["slop proxy (AI, closed unmerged)"]):
    md.append(f"| `{t['signal']}` | {t['where']} | {t['desc']} | {pct(t['slop proxy (AI, closed unmerged)'])} | "
              f"{pct(t['AI merged'])} | {pct(t['all merged'])} | {pct(t['non-AI closed'])} |")
md += ["", "## Best combination under a false-flag budget (greedy)", "",
       "| Scope and budget (share of merged PRs flagged) | Signals | Slop proxy caught | Merged flagged | AI merged flagged |",
       "|---|---|---:|---:|---:|"]
for k, v in greedy.items():
    md.append(f"| {k} | {', '.join(f'`{s}`' for s in v['signals'])} | {pct(v['slop_recall'])} | "
              f"{pct(v['merged_flag_rate'])} | {pct(v['ai_merged_flag_rate'])} |")
pt = out["patch_tier"]
md += ["", f"Patch tier only ({pt['slop']} slop-proxy and {pt['merged']} merged PRs with stored patches): any "
       f"change-policy patch policy catches {pct(pt['any_patch_policy_recall'])} of the slop proxy and flags "
       f"{pct(pt['any_patch_policy_merged'])} of merged PRs.", ""]
Path(ARGS.out_md).parent.mkdir(parents=True, exist_ok=True)
Path(ARGS.out_md).write_text("\n".join(md) + "\n", encoding="utf-8")
print("\n".join(md))
