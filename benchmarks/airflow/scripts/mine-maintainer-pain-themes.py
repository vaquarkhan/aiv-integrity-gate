# Copyright 2026 Vaquar Khan
# SPDX-License-Identifier: Apache-2.0
"""Count maintainer pain themes in the stored Airflow dev-list threads and issue comments.

Keyword counts are a coarse index for finding threads to read; the analysis in
docs/AI-integration/MAINTAINER-PAIN-AND-PR-PATTERNS-ANALYSIS.md is based on reading the threads.

    python benchmarks/airflow/scripts/mine-maintainer-pain-themes.py [--bench ../aiv-airflow-bench] [--out <dir>]
"""
import argparse
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

_ap = argparse.ArgumentParser()
_ap.add_argument("--bench", default=str(Path(__file__).resolve().parents[4] / "aiv-airflow-bench"))
_ap.add_argument("--out", default=None)
ARGS = _ap.parse_args()
C = Path(ARGS.bench) / "corpus"
THEMES = {
    "size / split": r"\b(too (big|large)|huge (pr|change|diff)|split (it|this|the pr|into)|smaller (prs?|chunks|pieces)|massive (pr|change)|hundreds of (files|lines)|\d{2,} files)\b",
    "unrelated / scope": r"\b(unrelated (change|file)s?|out of scope|scope creep|not related to|drive-by|unnecessary changes?)\b",
    "duplicate / reinvent": r"\b(already exists?|already (have|has|implemented)|duplicat(e|es|ed|ion)|reinvent|same as existing|there is already)\b",
    "not run / not tested": r"\b(did(n't| not) (run|test)|not tested|untested|doesn't (compile|work|run)|tests? (fail|are failing|broke)|ci (fails|is red))\b",
    "hallucination": r"\b(hallucinat\w*|does not exist|doesn't exist|no such (method|function|module|api|class)|made[- ]up|invented)\b",
    "no understanding / no engagement": r"\b(can you explain|do you understand|did you (read|review)|copy[- ]?past(e|ed)|not respond\w*|no response|ghost\w*|abandon\w*)\b",
    "volume / maintainer time": r"\b(waste of (time|review)|reviewer time|maintainers?'? time|burden|flood\w*|spam\w*|overwhelm\w*|burn ?out|too many prs|volume)\b",
    "description mismatch": r"\b(description (does not|doesn't) match|pr description|misleading|claims? to fix|doesn't fix|does not fix)\b",
    "disclosure / policy": r"\b(generated-by|disclos\w*|ai policy|gen-?ai|asf policy|co-authored-by)\b",
    "slop wording": r"\b(ai slop|slop|low[- ]quality|llm[- ]generated|chatgpt|ai[- ]generated)\b",
}
RX = {k: re.compile(v, re.I) for k, v in THEMES.items()}


def texts():
    for f in (C / "ai-year" / "mail-threads").glob("*.json"):
        t = json.loads(f.read_text(encoding="utf-8"))
        for m in t.get("messages", []):
            body = "\n".join(l for l in (m.get("body") or "").split("\n") if not l.lstrip().startswith(">"))
            yield "mail", t.get("subject"), m.get("date"), body
    for f in (C / "ai-year" / "issues").glob("*.json"):
        t = json.loads(f.read_text(encoding="utf-8"))
        iss = t.get("issue", {})
        for c in t.get("comments", []):
            yield "issue-comment", f"#{iss.get('number')} {iss.get('title')}", c.get("created_at"), c.get("body") or ""


hits = Counter()
docs = Counter()
snips = defaultdict(list)
n = 0
for kind, subj, date, body in texts():
    n += 1
    docs[kind] += 1
    for k, rx in RX.items():
        m = rx.search(body)
        if m:
            hits[(kind, k)] += 1
            if len(snips[k]) < 8:
                s = max(0, m.start() - 160)
                snips[k].append(f"[{kind} {str(date)[:10]} | {str(subj)[:60]}] ..." + re.sub(r"\s+", " ", body[s:m.end() + 160]) + "...")
print("documents:", dict(docs))
for k in THEMES:
    print(f"{k:34} mail={hits[('mail', k)]:4} ({100*hits[('mail', k)]/max(1,docs['mail']):4.1f}%)  "
          f"issue-comments={hits[('issue-comment', k)]:4} ({100*hits[('issue-comment', k)]/max(1,docs['issue-comment']):4.1f}%)")
if ARGS.out:
    Path(ARGS.out).mkdir(parents=True, exist_ok=True)
    (Path(ARGS.out) / "pain-snippets.json").write_text(json.dumps(snips, indent=1, ensure_ascii=False), encoding="utf-8")
