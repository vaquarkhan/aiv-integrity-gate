#!/usr/bin/env python3
"""
Label abandoned (closed-unmerged) Airflow PRs, recreate them under AIV, benchmark.

Label classes (honest taxonomy — not Magpie authorship):
  ai_slop_objective   — objective markers in patch (conflict / SEARCH-REPLACE / elision / YOUR_CODE)
  ai_disclosed        — confirmed AI tooling disclosure in PR body (ChatGPT/Claude/Codex + Yes)
  ai_signal_hard      — AIV hard-fail without the above (candidate defect / slop signal)
  ai_signal_advisory  — AIV advisory only (cohesion/density warn)
  abandoned_clean     — no markers, no disclosure, AIV hard-pass

Primary claim this supports:
  Most abandoned PRs are NOT marker-style AI slop; among those labeled as AI-signal,
  report what % AIV identifies when the same code is pushed through the gate.
"""
from __future__ import annotations

import argparse
import datetime as dt
import html
import json
import os
import re
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

from importlib.machinery import SourceFileLoader

e = SourceFileLoader(
    "e2e",
    str(Path(__file__).resolve().parent / "run-e2e-benchmark.py"),
).load_module()

BENCH = e.BENCH
REPORTS = BENCH / "reports"
CORPUS = BENCH / "corpus"
REPO = "apache/airflow"
WINDOW = "2026-06-20..2026-09-18"
BENCH_REPO = "vaquarkhan/aiv-airflow-bench"


def run(cmd, cwd=None, check=True):
    r = subprocess.run(cmd, cwd=cwd, text=True, encoding="utf-8", errors="replace", capture_output=True)
    if check and r.returncode != 0:
        raise RuntimeError((r.stderr or r.stdout or "")[-2000:])
    return (r.stdout or "").strip()


def content_from_patch(patch):
    if not patch:
        return None
    lines = []
    for line in patch.splitlines():
        if line.startswith(("+++", "---", "@@")):
            continue
        if line.startswith("+"):
            lines.append(line[1:])
        elif line.startswith("-") or line.startswith("\\"):
            continue
        else:
            lines.append(line[1:] if line.startswith(" ") else line)
    return "\n".join(lines) + ("\n" if lines else "")


def true_disclosure(body: str) -> dict:
    b = body or ""
    signals = []
    if re.search(
        r"(?im)^\s*(?:[-*]\s*)?\[\s*[xX]\s*\].{0,120}(chatgpt|claude|codex|copilot|gemini|generative ai)",
        b,
    ):
        signals.append("checkbox_yes")
    if re.search(
        r"(?im)^Generated-by:\s*(?!\[Tool Name\])(?!\s*$)\S+",
        b,
    ):
        # exclude empty / placeholder template
        m = re.search(r"(?im)^Generated-by:\s*(.+)$", b)
        val = (m.group(1) if m else "").strip()
        if val and "[Tool Name]" not in val and not val.lower().startswith("following"):
            signals.append("generated_by_filled")
        elif val and re.search(r"(?i)chatgpt|claude|codex|copilot|gemini", val):
            signals.append("generated_by_filled")
    if re.search(r"(?im)^Generated-by:\s*.*(chatgpt|claude|codex|copilot|gemini)", b):
        if "generated_by_filled" not in signals:
            signals.append("generated_by_filled")
    return {
        "signals": signals,
        "disclosed": "checkbox_yes" in signals or "generated_by_filled" in signals,
    }


def assign_label(markers, disclosed, hard, advisory) -> str:
    if markers:
        return "ai_slop_objective"
    if disclosed:
        return "ai_disclosed"
    if hard:
        return "ai_signal_hard"
    if advisory:
        return "ai_signal_advisory"
    return "abandoned_clean"


def score_one(jar, number: int, work_root: Path) -> dict:
    work = Path(tempfile.mkdtemp(prefix=f"abd-{number}-", dir=work_root))
    try:
        base = e.materialize_pr_workspace(REPO, number, work)
        out = work / "aiv.json"
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
        return {
            "hard_fail": hard,
            "advisory": bool(advisory),
            "blocking_gates": blocking,
            "advisory_gates": advisory,
            "exit_code": report.get("_exit_code"),
        }
    finally:
        shutil.rmtree(work, ignore_errors=True)


def inject_pr(tmp: Path, row: dict) -> int | None:
    n = int(row["pr"])
    label = row["label"]
    branch = f"abandoned/{label}/airflow-pr-{n}"
    title = f"[{label}] airflow#{n}: {(row.get('title') or '')[:50]}"
    run(["git", "checkout", "main", "-q"], cwd=tmp)
    run(["git", "checkout", "-B", branch], cwd=tmp)
    # clear prior inject paths
    for p in ["providers", "provenance"]:
        pp = tmp / p
        if pp.exists():
            shutil.rmtree(pp)
    prov = tmp / "provenance" / f"airflow-pr-{n}"
    prov.mkdir(parents=True, exist_ok=True)
    (prov / "label.json").write_text(json.dumps(row, indent=2) + "\n", encoding="utf-8")
    raw = subprocess.check_output(
        ["gh", "api", f"repos/{REPO}/pulls/{n}/files", "--paginate"]
    )
    files = json.loads(raw.decode("utf-8", errors="replace"))
    if not isinstance(files, list):
        files = [files]
    written = 0
    for f in files:
        if f.get("status") == "removed" or not f.get("filename"):
            continue
        text = content_from_patch(f.get("patch"))
        if text is None:
            continue
        dest = tmp / "providers" / "abandoned" / f"pr-{n}" / f["filename"]
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(text, encoding="utf-8", errors="replace")
        written += 1
        if written >= 25:
            break
    if written == 0:
        (prov / "NO_FILES.txt").write_text("no patchable files\n", encoding="utf-8")
    run(["git", "add", "-A"], cwd=tmp)
    run(["git", "commit", "-q", "-m", title[:72]], cwd=tmp)
    run(["git", "push", "-u", "origin", branch, "--force"], cwd=tmp)
    existing = subprocess.run(
        ["gh", "pr", "list", "--repo", BENCH_REPO, "--head", branch, "--json", "number", "-q", ".[0].number"],
        text=True,
        capture_output=True,
    ).stdout.strip()
    if not existing:
        body = (
            f"## Abandoned Airflow PR recreated under AIV\n\n"
            f"- Origin: {row.get('url')}\n"
            f"- **Label:** `{label}`\n"
            f"- Disclosure: {row.get('disclosure')}\n"
            f"- Markers: {row.get('markers_in_patch')}\n"
            f"- Local AIV: hard={row.get('aiv',{}).get('hard_fail')} "
            f"block={row.get('aiv',{}).get('blocking_gates')} "
            f"adv={row.get('aiv',{}).get('advisory_gates')}\n"
        )
        out = run(
            [
                "gh", "pr", "create", "--repo", BENCH_REPO,
                "--base", "main", "--head", branch,
                "--title", title[:80], "--body", body,
            ],
            cwd=tmp,
        )
        # parse URL trailing number if possible
        m = re.search(r"/pull/(\d+)", out)
        return int(m.group(1)) if m else None
    return int(existing) if existing.isdigit() else None


def write_report(summary: dict, path: Path) -> None:
    c = summary["claims"]
    rows = summary["cases"]
    by = c.get("by_label") or {}
    cards = "".join(
        f"<div class='card'><div>{html.escape(k)}</div><strong>{v}</strong></div>"
        for k, v in by.items()
    )
    trs = []
    for r in rows:
        a = r.get("aiv") or {}
        bench = r.get("bench_pr_url") or ""
        origin = r.get("url") or ""
        bench_cell = f'<a href="{html.escape(bench)}">bench</a>' if bench else ""
        trs.append(
            "<tr>"
            f"<td><a href='{html.escape(origin)}'>#{r.get('pr')}</a></td>"
            f"<td><code>{html.escape(r.get('label',''))}</code></td>"
            f"<td>{a.get('hard_fail')}</td>"
            f"<td>{html.escape(','.join(a.get('blocking_gates') or []))}</td>"
            f"<td>{html.escape(','.join(a.get('advisory_gates') or []))}</td>"
            f"<td>{html.escape(','.join(r.get('markers_in_patch') or []))}</td>"
            f"<td>{'yes' if r.get('disclosure',{}).get('disclosed') else 'no'}</td>"
            f"<td>{bench_cell}</td>"
            f"<td>{html.escape((r.get('title') or '')[:70])}</td>"
            "</tr>"
        )
    doc = f"""<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"/>
<title>Abandoned Airflow PRs → labeled → AIV bench</title>
<style>
body{{font-family:Segoe UI,system-ui,sans-serif;background:#0f1419;color:#e7ecf3;margin:0;padding:2rem}}
a{{color:#3d9cf0}} .card{{display:inline-block;background:#1a2332;border:1px solid #2a3648;border-radius:10px;padding:1rem;margin:.35rem;min-width:8rem}}
table{{width:100%;border-collapse:collapse;font-size:.82rem}} th,td{{border-bottom:1px solid #2a3648;padding:.35rem;text-align:left;vertical-align:top}}
.note{{color:#e6c07b}} section{{background:#1a2332;border:1px solid #2a3648;border-radius:12px;padding:1rem;margin:1rem 0}}
code{{background:#0d1218;padding:.1rem .3rem;border-radius:4px}}
</style></head><body>
<h1>Abandoned Airflow PRs: label → recreate under AIV → catch rate</h1>
<p class="note">Generated {html.escape(summary.get('generated_at',''))}. Closed-unmerged ≠ AI authorship.</p>
{cards}
<section><h2>VERIFIED claims</h2>
<p>{html.escape(c.get('statement',''))}</p>
<ul>{''.join(f'<li>{html.escape(x)}</li>' for x in (c.get('bullets') or []))}</ul>
</section>
<section><h2>NOT CLAIMED</h2>
<ul>{''.join(f'<li>{html.escape(x)}</li>' for x in (c.get('not_claimed') or []))}</ul>
</section>
<section><h2>Every sampled abandoned PR (label + AIV + bench link)</h2>
<table><thead><tr><th>Origin</th><th>Label</th><th>Hard</th><th>Block</th><th>Advisory</th><th>Markers</th><th>Disclosed</th><th>Bench PR</th><th>Title</th></tr></thead>
<tbody>{''.join(trs)}</tbody></table></section>
</body></html>"""
    path.write_text(doc, encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=60, help="Max closed-unmerged to score (stratified)")
    ap.add_argument("--inject", action="store_true", help="Recreate labeled PRs on aiv-airflow-bench")
    ap.add_argument("--inject-clean", type=int, default=10, help="How many abandoned_clean controls to inject")
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()

    os.environ["AIV_CENSUS_MATERIALIZE"] = "patch"
    os.environ["AIV_CENSUS_AIV_CONFIG"] = str(BENCH / ".aiv" / "config-census-patch.yaml")

    index = json.loads((CORPUS / "census-index.json").read_text(encoding="utf-8"))
    closed = [p for p in index.get("prs", []) if p.get("cohort") == "closed_unmerged"]
    # Prefer recent first; include any with markers first
    closed.sort(key=lambda p: (0 if p.get("discovery", {}).get("markers_in_patch") else 1, -(p.get("pr") or 0)))
    sample = closed[: args.limit]

    # Always include known ChatGPT disclosed closed-unmerged
    must = {73124, 70250}
    have = {p["pr"] for p in sample}
    for p in closed:
        if p["pr"] in must and p["pr"] not in have:
            sample.append(p)

    out_json = REPORTS / "abandoned-labeled-latest.json"
    done = {}
    if args.resume and out_json.exists():
        prev = json.loads(out_json.read_text(encoding="utf-8"))
        done = {r["pr"]: r for r in prev.get("cases", []) if "aiv" in r}

    jar = e.find_jar()
    work_root = Path(tempfile.mkdtemp(prefix="aiv-abandoned-batch-"))
    rows = []
    try:
        for i, p in enumerate(sample, 1):
            n = int(p["pr"])
            if n in done:
                print(f"skip #{n} (resume)", flush=True)
                rows.append(done[n])
                continue
            print(f"[{i}/{len(sample)}] Label+score #{n}…", flush=True)
            body = ""
            try:
                body = (e.gh_api(f"repos/{REPO}/pulls/{n}") or {}).get("body") or ""
            except Exception:
                pass
            disc = true_disclosure(body)
            markers = (p.get("discovery") or {}).get("markers_in_patch") or []
            try:
                aiv = score_one(jar, n, work_root)
            except Exception as ex:  # noqa: BLE001
                aiv = {"error": str(ex), "hard_fail": False, "advisory": False, "blocking_gates": [], "advisory_gates": []}
                print(f"  ERROR {ex}", flush=True)
            label = assign_label(markers, disc["disclosed"], aiv.get("hard_fail"), aiv.get("advisory"))
            row = {
                "pr": n,
                "url": p.get("url") or f"https://github.com/{REPO}/pull/{n}",
                "title": p.get("title"),
                "markers_in_patch": markers,
                "disclosure": disc,
                "aiv": aiv,
                "label": label,
                "window": WINDOW,
            }
            rows.append(row)
            print(
                f"  -> label={label} hard={aiv.get('hard_fail')} adv={aiv.get('advisory')} "
                f"disc={disc['disclosed']} markers={markers}",
                flush=True,
            )
            # checkpoint
            tmp_sum = {"cases": rows}
            out_json.write_text(json.dumps(tmp_sum, indent=2) + "\n", encoding="utf-8")
            time.sleep(0.15)
    finally:
        shutil.rmtree(work_root, ignore_errors=True)

    by_label: dict[str, int] = {}
    for r in rows:
        by_label[r["label"]] = by_label.get(r["label"], 0) + 1

    n = len(rows)
    clean = by_label.get("abandoned_clean", 0)
    ai_like = n - clean
    hard = sum(1 for r in rows if (r.get("aiv") or {}).get("hard_fail"))
    # Catch rate among non-clean labels that we expect could be blocked:
    # objective + disclosed + hard-signal — for disclosed, hard is optional
    expect_identifiable = [
        r for r in rows
        if r["label"] in ("ai_slop_objective", "ai_signal_hard")
        or (r["label"] == "ai_disclosed" and (r.get("aiv") or {}).get("hard_fail"))
    ]
    # Better metric: among labels that ARE ai_slop_objective or ai_signal_hard, AIV identified 100% by definition for hard.
    # Among ai_disclosed: hard_fail rate
    disclosed_rows = [r for r in rows if r["label"] == "ai_disclosed"]
    disclosed_caught = [r for r in disclosed_rows if (r.get("aiv") or {}).get("hard_fail")]
    signal_rows = [r for r in rows if r["label"] != "abandoned_clean"]
    signal_hard = [r for r in signal_rows if (r.get("aiv") or {}).get("hard_fail")]

    claims = {
        "tag": "VERIFIED",
        "sampled_abandoned": n,
        "by_label": by_label,
        "abandoned_clean_pct": round(100 * clean / n, 1) if n else None,
        "ai_signal_pct": round(100 * ai_like / n, 1) if n else None,
        "hard_fail_among_sample": hard,
        "hard_fail_pct_among_sample": round(100 * hard / n, 1) if n else None,
        "disclosed_n": len(disclosed_rows),
        "disclosed_hard_caught": len(disclosed_caught),
        "disclosed_catch_rate": (len(disclosed_caught) / len(disclosed_rows)) if disclosed_rows else None,
        "ai_signal_n": len(signal_rows),
        "ai_signal_hard_identified": len(signal_hard),
        "bullets": [
            f"Of {n} abandoned Airflow PRs sampled, {clean} ({(100*clean/n) if n else 0:.1f}%) labeled abandoned_clean (no markers / disclosure / AIV hard).",
            f"AI-signal labels (objective/disclosed/hard/advisory): {ai_like} ({(100*ai_like/n) if n else 0:.1f}%).",
            f"AIV hard-fail on sample: {hard}/{n} ({(100*hard/n) if n else 0:.1f}%).",
            f"Confirmed disclosed in sample: {len(disclosed_rows)}; hard-caught {len(disclosed_caught)}.",
        ],
        "statement": (
            f"Most abandoned PRs in this sample are not marker-style AI slop: "
            f"{(100*clean/n) if n else 0:.1f}% labeled abandoned_clean. "
            f"When the same PR diffs are run through AIV, hard-fail identifies "
            f"{hard}/{n} ({(100*hard/n) if n else 0:.1f}%) of the sample; "
            f"among confirmed AI-disclosed abandoned PRs in-sample, "
            f"{len(disclosed_caught)}/{len(disclosed_rows) or 0} hard-blocked."
        ),
        "not_claimed": [
            "That abandoned_clean PRs have zero AI involvement",
            "That AI disclosure alone means the PR should be rejected",
            "Exact % of all 597 abandoned PRs that are AI authorship",
            "CI dollars saved",
        ],
    }

    # Inject into bench
    if args.inject:
        print("=== Recreating labeled PRs on aiv-airflow-bench ===", flush=True)
        to_inject = [r for r in rows if r["label"] != "abandoned_clean"]
        cleans = [r for r in rows if r["label"] == "abandoned_clean"][: args.inject_clean]
        to_inject = to_inject + cleans
        tmp = Path(tempfile.mkdtemp(prefix="aiv-abd-inject-"))
        try:
            run(["gh", "repo", "clone", BENCH_REPO, str(tmp), "--", "--depth", "1"])
            # refresh .aiv on main
            run(["git", "checkout", "main"], cwd=tmp)
            if (tmp / ".aiv").exists():
                shutil.rmtree(tmp / ".aiv")
            shutil.copytree(BENCH / ".aiv", tmp / ".aiv")
            wf = tmp / ".github" / "workflows"
            wf.mkdir(parents=True, exist_ok=True)
            shutil.copy2(BENCH / "workflows" / "aiv.yml", wf / "aiv.yml")
            run(["git", "config", "user.email", "bench@aiv.local"], cwd=tmp)
            run(["git", "config", "user.name", "AIV Bench"], cwd=tmp)
            run(["git", "add", "-A"], cwd=tmp)
            run(["git", "commit", "-q", "-m", "chore: refresh .aiv for abandoned labeled bench"], cwd=tmp, check=False)
            run(["git", "push", "origin", "main"], cwd=tmp, check=False)
            for r in to_inject:
                try:
                    prn = inject_pr(tmp, r)
                    if prn:
                        r["bench_pr"] = prn
                        r["bench_pr_url"] = f"https://github.com/{BENCH_REPO}/pull/{prn}"
                    print(f"  injected #{r['pr']} as {r.get('bench_pr_url')}", flush=True)
                except Exception as ex:  # noqa: BLE001
                    r["inject_error"] = str(ex)
                    print(f"  inject fail #{r['pr']}: {ex}", flush=True)
                time.sleep(0.35)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    summary = {
        "schema_version": 1,
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "window": WINDOW,
        "claims": claims,
        "cases": rows,
        "methodology": {
            "materialize": "patch",
            "config": "benchmarks/airflow/.aiv/config-census-patch.yaml",
            "label_taxonomy": [
                "ai_slop_objective",
                "ai_disclosed",
                "ai_signal_hard",
                "ai_signal_advisory",
                "abandoned_clean",
            ],
        },
    }
    REPORTS.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    write_report(summary, REPORTS / "abandoned-labeled-latest.html")
    # also write master product report
    write_master_report(summary)
    print(json.dumps(claims, indent=2), flush=True)
    print(f"Wrote {out_json}", flush=True)
    return 0


def write_master_report(abandoned: dict) -> None:
    """Combine labeled-50 + abandoned labeling into one product-facing HTML."""
    labeled_path = REPORTS / "labeled-latest.json"
    labeled = json.loads(labeled_path.read_text(encoding="utf-8")) if labeled_path.exists() else {}
    lc = labeled.get("claims") or {}
    ac = abandoned.get("claims") or {}
    chatgpt_path = REPORTS / "airflow-chatgpt-true-latest.json"
    chatgpt = json.loads(chatgpt_path.read_text(encoding="utf-8")) if chatgpt_path.exists() else {}
    cc = chatgpt.get("claims") or {}

    doc = f"""<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"/>
<title>AIV product benchmark report</title>
<style>
body{{font-family:Segoe UI,system-ui,sans-serif;background:#0f1419;color:#e7ecf3;margin:0;padding:2rem;max-width:1100px}}
a{{color:#3d9cf0}} .card{{display:inline-block;background:#1a2332;border:1px solid #2a3648;border-radius:10px;padding:1rem;margin:.4rem;min-width:10rem}}
section{{background:#1a2332;border:1px solid #2a3648;border-radius:12px;padding:1.2rem;margin:1.2rem 0}}
.note{{color:#e6c07b}} h1{{margin-top:0}} strong{{color:#fff}}
</style></head><body>
<h1>AIV Integrity Gate — product benchmark</h1>
<p class="note">Updated {html.escape(abandoned.get('generated_at',''))}. Three layers: labeled fixtures → abandoned Airflow labeling → live bench recreation.</p>

<section>
<h2>1) Labeled 50-case fixtures (primary product proof)</h2>
<div class="card">Recall<br><strong>{html.escape(str(round(100*(lc.get('hard_gate_recall') or 0),1)))}%</strong></div>
<div class="card">FP rate<br><strong>{html.escape(str(round(100*(lc.get('false_positive_rate') or 0),1)))}%</strong></div>
<div class="card">Precision<br><strong>{html.escape(str(round(100*(lc.get('hard_gate_precision') or 0),1)))}%</strong></div>
<p>{html.escape(lc.get('statement',''))}</p>
<p><a href="labeled-latest.html">Full 50-case table</a> · Live: <a href="https://github.com/vaquarkhan/aiv-airflow-bench">aiv-airflow-bench</a></p>
</section>

<section>
<h2>2) Abandoned Airflow PRs — what is AI slop? (labels)</h2>
<p>{html.escape(ac.get('statement',''))}</p>
<div class="card">Sampled abandoned<br><strong>{ac.get('sampled_abandoned')}</strong></div>
<div class="card">abandoned_clean<br><strong>{(ac.get('by_label') or {}).get('abandoned_clean',0)}</strong>
 ({ac.get('abandoned_clean_pct')}%)</div>
<div class="card">AI-signal labels<br><strong>{ac.get('ai_signal_pct')}%</strong></div>
<div class="card">AIV hard-fail<br><strong>{ac.get('hard_fail_among_sample')}/{ac.get('sampled_abandoned')}</strong></div>
<p>Label taxonomy: <code>ai_slop_objective</code>, <code>ai_disclosed</code>, <code>ai_signal_hard</code>,
<code>ai_signal_advisory</code>, <code>abandoned_clean</code>.</p>
<p><a href="abandoned-labeled-latest.html">Per-PR labels + bench links</a></p>
</section>

<section>
<h2>3) Confirmed ChatGPT disclosure (tiny gold set)</h2>
<p>{html.escape(cc.get('statement',''))}</p>
</section>

<section>
<h2>NOT CLAIMED</h2>
<ul>
<li>That closed-unmerged volume equals AI authorship</li>
<li>That AIV clears X% of AIP-120's 508 as a measured CI savings</li>
<li>That search <code>Generated-by</code> totals are AI-slop counts (template noise)</li>
</ul>
</section>
</body></html>"""
    (REPORTS / "product-benchmark-latest.html").write_text(doc, encoding="utf-8")
    (REPORTS / "product-benchmark-latest.json").write_text(
        json.dumps(
            {
                "generated_at": abandoned.get("generated_at"),
                "labeled_50": lc,
                "abandoned_labeled": ac,
                "chatgpt_true": cc,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    raise SystemExit(main())
