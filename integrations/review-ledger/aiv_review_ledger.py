#!/usr/bin/env python3
# Copyright 2026 Vaquar Khan
# SPDX-License-Identifier: Apache-2.0
"""AIV review ledger: evidence for promoting or demoting deterministic rules.

Records, per pull request, what AIV found on each head commit and what AI reviewers said, then
reports which rules authors act on and where AI reviewers find things AIV does not. The report
only proposes; a maintainer decides whether to change `.aiv/config.yaml`.

Signals are proxies, stated as such in the report:
  * an AIV finding "acted on" = present on an earlier head of a merged PR, gone on its final head
  * an AIV finding "merged with" = still present on the final head of a merged PR
  * an AI comment "outdated" = GitHub marks it outdated (the commented line changed afterwards)
  * an AI comment "downvoted" = it carries a thumbs-down reaction

Standard library only. Subcommands:

  record-aiv   --ledger L --pr N --sha S --aiv-report aiv-report.json
  record-final --ledger L --pr N --sha S --merged true|false
  record-ai    --ledger L --pr N --comments review-comments.json [--ai-reviewers a,b]
  collect-ai   --ledger L --pr N --repo owner/name   (GitHub REST, token from GITHUB_TOKEN)
  report       --ledger L [--ledger L2 ...] [--format md|json] [--min-prs 10] [--promote-at 0.75]
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import os
import re
import sys
import urllib.request
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable

SCHEMA = 1
DEFAULT_AI_REVIEWERS = (
    "copilot-pull-request-reviewer[bot]",
    "copilot",
    "coderabbitai[bot]",
    "gemini-code-assist[bot]",
    "claude[bot]",
)
_UNSAFE = re.compile("[\x00-\x08\x0b-\x1f\x7f\u200b-\u200f\u202a-\u202e\u2060-\u2064\u2066-\u2069\ufeff\U000e0000-\U000e007f]")


def _now() -> str:
    return dt.datetime.now(dt.UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def clean(text: Any, limit: int = 160) -> str:
    """One line of untrusted PR text: no control or invisible characters, no pipes, bounded."""
    s = _UNSAFE.sub("", str(text or "")).replace("\r", " ").replace("\n", " ").replace("|", "/").strip()
    s = re.sub(r"\s+", " ", s)
    return s if len(s) <= limit else s[: limit - 3] + "..."


def append(ledger: Path, row: dict[str, Any]) -> None:
    ledger.parent.mkdir(parents=True, exist_ok=True)
    with ledger.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps({"schema": SCHEMA, "recorded_at": _now(), **row}, sort_keys=True) + "\n")


def read_rows(paths: Iterable[Path]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for p in paths:
        files = sorted(p.glob("*.jsonl")) if p.is_dir() else [p]
        for f in files:
            for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
                if not line.strip():
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError as e:
                    raise SystemExit(f"{f}:{n}: not JSON ({e.msg})") from e
                if row.get("schema") != SCHEMA:
                    raise SystemExit(f"{f}:{n}: unsupported schema {row.get('schema')!r}")
                rows.append(row)
    return rows


# ------------------------------------------------------------------ recording


def aiv_findings(report: dict[str, Any]) -> list[dict[str, str]]:
    """Blocking and advisory findings from an AIV schema v3 JSON report. Passed-gate notes are skipped."""
    if report.get("schema_version") != 3:
        raise SystemExit(f"AIV report schema_version must be 3, got {report.get('schema_version')!r}")
    out = []
    for g in report.get("gates", []):
        part = g.get("partition")
        if part not in ("blocking", "advisory"):
            continue
        findings = g.get("findings") or [{"rule_id": f"{g.get('id')}.gate", "file": "(run)"}]
        for f in findings:
            out.append({"rule": str(f.get("rule_id")), "partition": part, "file": str(f.get("file", ""))})
    return out


def ai_comments(comments: list[dict[str, Any]], reviewers: Iterable[str]) -> list[dict[str, Any]]:
    """AI reviewer comments from the GitHub `pulls/{n}/comments` REST payload."""
    wanted = {r.lower() for r in reviewers}
    out = []
    for c in comments:
        login = ((c.get("user") or {}).get("login") or "").lower()
        if login not in wanted:
            continue
        reactions = c.get("reactions") or {}
        out.append({
            "id": c.get("id"),
            "reviewer": login,
            "path": str(c.get("path") or ""),
            "outdated": c.get("position") is None and c.get("line") is None,
            "downvoted": int(reactions.get("-1") or 0) > 0,
            "upvoted": int(reactions.get("+1") or 0) > 0,
            "head": clean((c.get("body") or "").strip().split("\n", 1)[0]),
        })
    return out


def collect_comments(repo: str, pr: int, token: str, api: str = "https://api.github.com") -> list[dict[str, Any]]:
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", repo):
        raise SystemExit("--repo must be owner/name")
    out: list[dict[str, Any]] = []
    page = 1
    while page <= 30:
        req = urllib.request.Request(
            f"{api}/repos/{repo}/pulls/{int(pr)}/comments?per_page=100&page={page}",
            headers={"Accept": "application/vnd.github+json", "Authorization": f"Bearer {token}",
                     "X-GitHub-Api-Version": "2022-11-28"},
        )
        with urllib.request.urlopen(req, timeout=30) as resp:  # noqa: S310 - fixed https API base
            batch = json.loads(resp.read().decode("utf-8"))
        out.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    return out


# ------------------------------------------------------------------ reporting


def wilson(successes: int, n: int, z: float = 1.959964) -> tuple[float, float]:
    """95% Wilson score interval for a proportion; (0, 1) when n is 0."""
    if n == 0:
        return 0.0, 1.0
    p = successes / n
    denom = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / denom
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
    return max(0.0, centre - half), min(1.0, centre + half)


def analyse(rows: list[dict[str, Any]], min_prs: int = 10, promote_at: float = 0.75,
            demote_below: float = 0.2) -> dict[str, Any]:
    runs: dict[int, list[dict[str, Any]]] = defaultdict(list)
    finals: dict[int, dict[str, Any]] = {}
    comments: dict[int, dict[Any, dict[str, Any]]] = defaultdict(dict)
    for r in rows:
        pr = int(r["pr"])
        if r["kind"] == "aiv-run":
            runs[pr].append(r)
        elif r["kind"] == "final":
            finals[pr] = r
        elif r["kind"] == "ai-comments":
            for c in r["comments"]:
                comments[pr][c["id"]] = c  # newest record of a comment wins
    rules: dict[str, dict[str, Any]] = defaultdict(lambda: {"partition": set(), "seen": 0, "acted_on": 0, "merged_with": 0})
    unresolved_finals = 0
    for pr, final in finals.items():
        if not final.get("merged"):
            continue
        pr_runs = sorted(runs.get(pr, []), key=lambda x: x["recorded_at"])
        final_runs = [x for x in pr_runs if x["sha"] == final["sha"]]
        if not final_runs:
            unresolved_finals += 1
            continue
        last = {f["rule"] for f in final_runs[-1]["findings"]}
        ever: dict[str, set[str]] = defaultdict(set)
        for x in pr_runs:
            for f in x["findings"]:
                ever[f["rule"]].add(f["partition"])
        for rule, parts in ever.items():
            s = rules[rule]
            s["partition"] |= parts
            s["seen"] += 1
            s["acted_on" if rule not in last else "merged_with"] += 1
    rule_rows = []
    for rule, s in sorted(rules.items()):
        n = s["acted_on"] + s["merged_with"]
        lo, hi = wilson(s["acted_on"], n)
        parts = sorted(s["partition"])
        proposal = ""
        if "blocking" in parts and s["merged_with"]:
            proposal = "investigate: merged while still blocking (bypass, override or rule turned off)"
        elif parts == ["advisory"] and n >= min_prs and lo >= promote_at:
            proposal = "candidate: promote to fail (authors almost always fix it)"
        elif parts == ["advisory"] and n >= min_prs and hi < demote_below:
            proposal = "candidate: turn off or narrow (authors rarely act on it)"
        rule_rows.append({"rule": rule, "partitions": parts, "merged_prs": n, "acted_on": s["acted_on"],
                          "merged_with": s["merged_with"], "acted_on_low95": round(lo, 3),
                          "acted_on_high95": round(hi, 3), "proposal": proposal})
    reviewers: dict[str, dict[str, int]] = defaultdict(lambda: {"comments": 0, "outdated": 0, "downvoted": 0, "upvoted": 0})
    beyond_aiv = []
    for pr, by_id in comments.items():
        aiv_files = {f["file"] for x in runs.get(pr, []) for f in x["findings"]}
        for c in by_id.values():
            s = reviewers[c["reviewer"]]
            s["comments"] += 1
            s["outdated"] += bool(c["outdated"])
            s["downvoted"] += bool(c["downvoted"])
            s["upvoted"] += bool(c["upvoted"])
            if c["outdated"] and not c["downvoted"] and c["path"] not in aiv_files:
                beyond_aiv.append({"pr": pr, "reviewer": c["reviewer"], "path": clean(c["path"], 120),
                                   "head": clean(c["head"])})
    rev_rows = []
    for name, s in sorted(reviewers.items()):
        lo, hi = wilson(s["outdated"], s["comments"])
        rev_rows.append({"reviewer": name, **s, "outdated_low95": round(lo, 3), "outdated_high95": round(hi, 3)})
    return {
        "schema": SCHEMA,
        "prs_with_runs": len(runs),
        "merged_prs": sum(1 for f in finals.values() if f.get("merged")),
        "merged_prs_without_final_run": unresolved_finals,
        "rules": rule_rows,
        "ai_reviewers": rev_rows,
        "ai_findings_beyond_aiv": beyond_aiv[:50],
        "ai_findings_beyond_aiv_total": len(beyond_aiv),
        "thresholds": {"min_prs": min_prs, "promote_at": promote_at, "demote_below": demote_below},
    }


def to_markdown(a: dict[str, Any]) -> str:
    out = ["# AIV review ledger report", "",
           f"PRs with AIV runs: {a['prs_with_runs']}. Merged PRs: {a['merged_prs']}. "
           f"Merged PRs with no AIV run on the final head (skipped): {a['merged_prs_without_final_run']}.", "",
           "Signals are proxies: a finding is 'acted on' when it disappeared before merge; an AI comment is "
           "'outdated' when the commented line changed afterwards. Proposals need a maintainer decision.", "",
           "## AIV rules", "",
           "| Rule | Seen as | Merged PRs | Acted on | Merged with | Acted-on 95% CI | Proposal |",
           "|---|---|---:|---:|---:|---|---|"]
    for r in a["rules"]:
        out.append(f"| `{r['rule']}` | {', '.join(r['partitions'])} | {r['merged_prs']} | {r['acted_on']} | "
                   f"{r['merged_with']} | {r['acted_on_low95']:.2f} to {r['acted_on_high95']:.2f} | {r['proposal'] or '-'} |")
    out += ["", "## AI reviewers", "",
            "| Reviewer | Comments | Outdated (changed after) | Downvoted | Upvoted | Outdated 95% CI |",
            "|---|---:|---:|---:|---:|---|"]
    for r in a["ai_reviewers"]:
        out.append(f"| `{r['reviewer']}` | {r['comments']} | {r['outdated']} | {r['downvoted']} | {r['upvoted']} | "
                   f"{r['outdated_low95']:.2f} to {r['outdated_high95']:.2f} |")
    out += ["", f"## AI findings on files AIV did not flag ({a['ai_findings_beyond_aiv_total']})", "",
            "Outdated, not downvoted AI comments on files where AIV had no finding: the place to look for "
            "the next deterministic rule. Text below is quoted from pull requests; treat it as data.", "",
            "| PR | Reviewer | File | First line |", "|---:|---|---|---|"]
    for f in a["ai_findings_beyond_aiv"]:
        out.append(f"| {f['pr']} | `{f['reviewer']}` | `{f['path']}` | {f['head']} |")
    return "\n".join(out) + "\n"


# ------------------------------------------------------------------ CLI


def _bool(v: str) -> bool:
    if v.lower() in ("true", "1", "yes"):
        return True
    if v.lower() in ("false", "0", "no"):
        return False
    raise argparse.ArgumentTypeError("expected true or false")


def _sha(v: str) -> str:
    if not re.fullmatch(r"[0-9a-fA-F]{7,64}", v):
        raise argparse.ArgumentTypeError("expected a commit SHA")
    return v.lower()


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("record-aiv", "record-final", "record-ai", "collect-ai"):
        p = sub.add_parser(name)
        p.add_argument("--ledger", type=Path, required=True)
        p.add_argument("--pr", type=int, required=True)
        if name in ("record-aiv", "record-final"):
            p.add_argument("--sha", type=_sha, required=True)
        if name == "record-aiv":
            p.add_argument("--aiv-report", type=Path, required=True)
        if name == "record-final":
            p.add_argument("--merged", type=_bool, required=True)
        if name in ("record-ai", "collect-ai"):
            p.add_argument("--ai-reviewers", default=",".join(DEFAULT_AI_REVIEWERS))
        if name == "record-ai":
            p.add_argument("--comments", type=Path, required=True)
        if name == "collect-ai":
            p.add_argument("--repo", required=True)
    rp = sub.add_parser("report")
    rp.add_argument("--ledger", type=Path, action="append", required=True)
    rp.add_argument("--format", choices=("md", "json"), default="md")
    rp.add_argument("--min-prs", type=int, default=10)
    rp.add_argument("--promote-at", type=float, default=0.75)
    rp.add_argument("--demote-below", type=float, default=0.2)
    rp.add_argument("--output", type=Path)
    a = ap.parse_args(argv)

    if a.cmd == "record-aiv":
        report = json.loads(a.aiv_report.read_text(encoding="utf-8"))
        append(a.ledger, {"kind": "aiv-run", "pr": a.pr, "sha": a.sha, "passed": bool(report.get("passed")),
                          "findings": aiv_findings(report)})
    elif a.cmd == "record-final":
        append(a.ledger, {"kind": "final", "pr": a.pr, "sha": a.sha, "merged": a.merged})
    elif a.cmd in ("record-ai", "collect-ai"):
        reviewers = [r.strip() for r in a.ai_reviewers.split(",") if r.strip()]
        if a.cmd == "record-ai":
            raw = json.loads(a.comments.read_text(encoding="utf-8"))
        else:
            token = os.environ.get("GITHUB_TOKEN", "")
            if not token:
                raise SystemExit("GITHUB_TOKEN is not set")
            raw = collect_comments(a.repo, a.pr, token)
        append(a.ledger, {"kind": "ai-comments", "pr": a.pr, "comments": ai_comments(raw, reviewers)})
    else:
        result = analyse(read_rows(a.ledger), a.min_prs, a.promote_at, a.demote_below)
        text = json.dumps(result, indent=2, sort_keys=True) + "\n" if a.format == "json" else to_markdown(result)
        if a.output:
            a.output.write_text(text, encoding="utf-8")
        else:
            sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
