#!/usr/bin/env python3
"""
Sample real apache/airflow PRs with AI-disclosure signals, materialize into AIV,
and measure catch rate.

Labels here = disclosure / search signal — NOT ground-truth 'this PR is worthless'.
"""
from __future__ import annotations

import datetime as dt
import json
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from importlib.machinery import SourceFileLoader

_e2e = SourceFileLoader(
    "e2e",
    str(Path(__file__).resolve().parent / "run-e2e-benchmark.py"),
).load_module()

ROOT = _e2e.ROOT
BENCH = _e2e.BENCH
REPORTS = BENCH / "reports"
WINDOW = "2026-06-20..2026-09-18"
REPO = "apache/airflow"


def gh_search(query: str, hard_cap: int = 100) -> tuple[int, list[dict]]:
    items: list[dict] = []
    page = 1
    total = 0
    while len(items) < hard_cap and page <= 10:
        data = _e2e.gh_api(
            "search/issues",
            {"q": query, "per_page": "30", "page": str(page), "sort": "created", "order": "desc"},
        )
        if page == 1:
            total = int(data.get("total_count") or 0)
            print(f"  total_count={total} q={query}", flush=True)
        batch = data.get("items") or []
        if not batch:
            break
        items.extend(batch)
        if len(batch) < 30 or len(items) >= total:
            break
        page += 1
        time.sleep(0.6)
    # de-dupe
    seen = set()
    out = []
    for it in items:
        n = it["number"]
        if n in seen:
            continue
        seen.add(n)
        out.append(it)
        if len(out) >= hard_cap:
            break
    return total, out


def pr_body(number: int) -> str:
    try:
        data = _e2e.gh_api(f"repos/{REPO}/pulls/{number}")
        return data.get("body") or ""
    except Exception:
        return ""


def classify_disclosure(body: str) -> dict:
    b = body or ""
    bl = b.lower()
    signals = []
    if re.search(r"(?im)^generated-by:\s*\S+", b):
        signals.append("generated-by_trailer")
    if "chatgpt" in bl:
        signals.append("mentions_chatgpt")
    if "copilot" in bl:
        signals.append("mentions_copilot")
    if "claude" in bl or "anthropic" in bl:
        signals.append("mentions_claude")
    if "codex" in bl:
        signals.append("mentions_codex")
    # Airflow checkbox patterns
    if re.search(r"(?i)\[\s*[xX]\s*\].{0,80}(generative ai|chatgpt|copilot|claude|codex)", b):
        signals.append("checkbox_yes_ai")
    if re.search(r"(?i)\[\s*\]\s*.{0,40}(generative ai|chatgpt)", b) and "checkbox_yes_ai" not in signals:
        signals.append("checkbox_unchecked_or_no")
    return {"signals": signals, "has_ai_disclosure": bool(set(signals) & {
        "generated-by_trailer", "mentions_chatgpt", "mentions_copilot", "mentions_claude",
        "mentions_codex", "checkbox_yes_ai",
    })}


def score_pr(jar: Path, number: int, work_root: Path) -> dict:
    work = Path(tempfile.mkdtemp(prefix=f"aiv-airflow-real-{number}-", dir=work_root))
    try:
        # Use head materialize for accuracy on samples
        base = _e2e.materialize_pr_workspace(REPO, number, work)
        out = work / "aiv-report.json"
        report = _e2e.run_aiv(jar, work, base, out)
        exit_code = report.get("_exit_code", 1)
        gates = report.get("gates") or []
        blocking = []
        advisory = []
        for g in gates:
            if g.get("passed", True):
                continue
            gid = g.get("id") or "?"
            if g.get("blocks_ci") is False:
                advisory.append(gid)
            else:
                blocking.append(gid)
        return {
            "hard_fail": exit_code == 1 or bool(blocking),
            "advisory": bool(advisory),
            "blocking_gates": blocking,
            "advisory_gates": advisory,
            "passed": report.get("passed"),
            "exit_code": exit_code,
        }
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main() -> int:
    print("=== Volume counts (AIP-120 window) ===", flush=True)
    totals = {}
    for name, q in [
        ("closed_unmerged", f"repo:{REPO} is:pr is:closed is:unmerged created:{WINDOW}"),
        ("merged", f"repo:{REPO} is:pr is:merged created:{WINDOW}"),
        ("closed_any", f"repo:{REPO} is:pr is:closed created:{WINDOW}"),
        ("generated_by", f"repo:{REPO} is:pr is:closed created:{WINDOW} Generated-by"),
        ("chatgpt", f"repo:{REPO} is:pr is:closed created:{WINDOW} ChatGPT"),
        ("copilot", f"repo:{REPO} is:pr is:closed created:{WINDOW} Copilot"),
    ]:
        total, _ = gh_search(q, hard_cap=1)
        totals[name] = total
        time.sleep(0.8)

    # Fetch candidates with Generated-by or ChatGPT in search (up to 40 each, merge)
    print("=== Fetch AI-signal candidates ===", flush=True)
    _, by_gen = gh_search(f"repo:{REPO} is:pr is:closed created:{WINDOW} Generated-by", hard_cap=40)
    time.sleep(1)
    _, by_gpt = gh_search(f"repo:{REPO} is:pr is:closed created:{WINDOW} ChatGPT", hard_cap=40)
    by_num: dict[int, dict] = {}
    for it in by_gen + by_gpt:
        by_num[it["number"]] = it

    # Prefer closed-unmerged
    candidates = []
    for n, it in sorted(by_num.items(), reverse=True):
        body = pr_body(n)
        disc = classify_disclosure(body)
        try:
            meta = _e2e.fetch_pr_meta(REPO, n)
        except Exception as e:
            print(f"  skip #{n}: {e}", flush=True)
            continue
        markers, _ = _e2e.scan_pr_markers(REPO, n)
        candidates.append({
            "pr": n,
            "url": f"https://github.com/{REPO}/pull/{n}",
            "title": it.get("title") or meta.get("title"),
            "merged": bool(meta.get("merged")),
            "state": meta.get("state"),
            "disclosure": disc,
            "markers_in_patch": markers,
            "additions": meta.get("additions"),
            "changed_files": meta.get("changed_files"),
        })
        time.sleep(0.25)

    disclosed = [c for c in candidates if c["disclosure"]["has_ai_disclosure"]]
    closed_unmerged_disc = [c for c in disclosed if not c["merged"]]
    print(f"Candidates fetched={len(candidates)} disclosed={len(disclosed)} "
          f"disclosed_closed_unmerged={len(closed_unmerged_disc)}", flush=True)

    # Score up to 25 disclosed closed-unmerged (or disclosed any if few)
    sample = closed_unmerged_disc[:25] or disclosed[:25]
    jar = _e2e.find_jar()
    # Force head materialize + airflow config (syntax on)
    import os
    os.environ["AIV_CENSUS_MATERIALIZE"] = "head"
    os.environ.pop("AIV_CENSUS_AIV_CONFIG", None)

    work_root = Path(tempfile.mkdtemp(prefix="aiv-real-airflow-batch-"))
    rows = []
    print(f"=== Scoring {len(sample)} disclosed PRs with AIV (head materialize) ===", flush=True)
    for i, c in enumerate(sample, 1):
        print(f"[{i}/{len(sample)}] #{c['pr']} {c['title'][:60]}…", flush=True)
        try:
            outcome = score_pr(jar, c["pr"], work_root)
        except Exception as e:  # noqa: BLE001
            outcome = {"error": str(e), "hard_fail": False, "advisory": False}
            print(f"  ERROR {e}", flush=True)
        row = {**c, "aiv": outcome}
        rows.append(row)
        print(
            f"  -> hard={outcome.get('hard_fail')} adv={outcome.get('advisory')} "
            f"block={outcome.get('blocking_gates')} markers={c['markers_in_patch']}",
            flush=True,
        )
        time.sleep(0.2)

    shutil.rmtree(work_root, ignore_errors=True)

    scored = [r for r in rows if "error" not in (r.get("aiv") or {})]
    hard = [r for r in scored if (r.get("aiv") or {}).get("hard_fail")]
    adv = [r for r in scored if (r.get("aiv") or {}).get("advisory")]
    marker_pos = [r for r in scored if r.get("markers_in_patch")]

    n = len(scored)
    claims = {
        "tag": "VERIFIED",
        "window": WINDOW,
        "volume": totals,
        "note": (
            "Search 'ChatGPT'/'Generated-by' counts are noisy (false hits in discussion). "
            "Disclosure-checked body sample is the scored cohort. "
            "Disclosure ≠ worthless PR; AIV hard-fail % here is catch-rate on disclosed sample, "
            "not '% of all Airflow AI slop resolved'."
        ),
        "disclosed_candidates_fetched": len(disclosed),
        "disclosed_closed_unmerged": len(closed_unmerged_disc),
        "scored": n,
        "hard_blocked": len(hard),
        "hard_block_rate": (len(hard) / n) if n else None,
        "advisory_only": len([r for r in scored if (r.get("aiv") or {}).get("advisory") and not (r.get("aiv") or {}).get("hard_fail")]),
        "objective_markers_in_patch": len(marker_pos),
        "statement": (
            f"Among {n} real Airflow PRs with AI-disclosure signals scored under AIV, "
            f"{len(hard)} hard-failed ({(100 * len(hard) / n):.1f}%). "
            f"Objective patch markers present in {len(marker_pos)}/{n}. "
            f"Window closed-unmerged volume={totals.get('closed_unmerged')}."
            if n else "No PRs scored."
        ),
        "not_claimed": [
            "That search ChatGPT totals equal AI-slop PRs",
            "That disclosure means the PR should be rejected",
            "% of all Airflow closed-unmerged that are AI slop",
            "CI dollars saved",
        ],
    }

    summary = {
        "schema_version": 1,
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "claims": claims,
        "cases": rows,
    }
    REPORTS.mkdir(parents=True, exist_ok=True)
    out = REPORTS / "airflow-real-disclosure-latest.json"
    out.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")

    # short HTML
    trs = []
    for r in rows:
        a = r.get("aiv") or {}
        trs.append(
            f"<tr><td><a href='{r['url']}'>#{r['pr']}</a></td>"
            f"<td>{'merged' if r.get('merged') else 'closed-unmerged'}</td>"
            f"<td>{','.join((r.get('disclosure') or {}).get('signals') or [])}</td>"
            f"<td>{a.get('hard_fail')}</td><td>{','.join(a.get('blocking_gates') or [])}</td>"
            f"<td>{','.join(r.get('markers_in_patch') or [])}</td>"
            f"<td>{(r.get('title') or '')[:80]}</td></tr>"
        )
    html = f"""<!DOCTYPE html><html><head><meta charset='utf-8'/><title>Airflow real disclosure sample</title>
<style>body{{font-family:Segoe UI,sans-serif;background:#0f1419;color:#e7ecf3;padding:2rem}}
table{{border-collapse:collapse;width:100%;font-size:.85rem}}td,th{{border-bottom:1px solid #2a3648;padding:.4rem;text-align:left}}
a{{color:#3d9cf0}}.card{{display:inline-block;background:#1a2332;padding:1rem;margin:.4rem;border-radius:10px}}</style></head><body>
<h1>Real Airflow AI-disclosure sample → AIV</h1>
<p>{claims['statement']}</p>
<div class='card'>Hard block rate<br><strong>{(100*(claims['hard_block_rate'] or 0)):.1f}%</strong> ({claims['hard_blocked']}/{claims['scored']})</div>
<div class='card'>Closed-unmerged volume<br><strong>{totals.get('closed_unmerged')}</strong></div>
<div class='card'>Search ChatGPT hits<br><strong>{totals.get('chatgpt')}</strong> (noisy)</div>
<table><thead><tr><th>PR</th><th>State</th><th>Disclosure</th><th>Hard</th><th>Gates</th><th>Markers</th><th>Title</th></tr></thead>
<tbody>{''.join(trs)}</tbody></table>
<p><em>{claims['note']}</em></p>
</body></html>"""
    (REPORTS / "airflow-real-disclosure-latest.html").write_text(html, encoding="utf-8")
    print(json.dumps(claims, indent=2), flush=True)
    print(f"Wrote {out}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
