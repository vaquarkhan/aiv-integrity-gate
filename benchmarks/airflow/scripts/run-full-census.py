#!/usr/bin/env python3
"""
Full AIP-120-window census of apache/airflow closed-unmerged PRs + AIV scoring.

Produces:
  corpus/census-index.json     — every PR (number, url, title, association, …)
  corpus/cases-census.json     — labeled cases for scoring
  reports/census-*/            — per-PR aiv-report.json + provenance
  reports/census-latest.html   — human report with block-rate + CI estimate math

Claims policy:
  - VERIFIED block_rate = hard_fails / scored among closed-unmerged in this census
  - ESTIMATED CI impact uses AIP-120 published mean job-minutes (documented assumptions)
  - Does NOT auto-label closed-unmerged as AI authorship
"""
from __future__ import annotations

import argparse
import datetime as dt
import html
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse
from pathlib import Path
from typing import Any

# Reuse helpers from sibling e2e script
sys.path.insert(0, str(Path(__file__).resolve().parent))
from importlib.machinery import SourceFileLoader

_e2e = SourceFileLoader(
    "e2e",
    str(Path(__file__).resolve().parent / "run-e2e-benchmark.py"),
).load_module()

ROOT = _e2e.ROOT
BENCH = _e2e.BENCH
REPORTS = BENCH / "reports"
CORPUS = BENCH / "corpus"
WINDOW_DEFAULT = "2026-06-20..2026-09-18"
# From AIP-120 wiki (public proposal) — used only for ESTIMATED extrapolation.
AIP120_EXTERNAL_CLOSED_UNMERGED = 508
AIP120_MEAN_JOB_MINUTES = 779  # sampled mean across PR runs in AIP-120 §3.2
AIP120_FULL_MATRIX_JOB_MINUTES = 4392
AIP120_DOCS_JOB_MINUTES = 31


def search_all_prs(query: str, hard_cap: int = 1000) -> list[dict[str, Any]]:
    """GitHub Search returns at most 1000 issues; paginate until exhausted."""
    items: list[dict[str, Any]] = []
    page = 1
    while len(items) < hard_cap and page <= 34:
        data = _e2e.gh_api(
            "search/issues",
            {
                "q": query,
                "per_page": "30",
                "page": str(page),
                "sort": "created",
                "order": "desc",
            },
        )
        batch = data.get("items") or []
        total = data.get("total_count", 0)
        if page == 1:
            print(f"Search total_count={total} query={query}", flush=True)
        if not batch:
            break
        items.extend(batch)
        if len(batch) < 30 or len(items) >= total:
            break
        page += 1
        time.sleep(0.5)
    # de-dupe by number
    seen: set[int] = set()
    out = []
    for it in items:
        n = it["number"]
        if n in seen:
            continue
        seen.add(n)
        out.append(it)
    return out


def build_index(args: argparse.Namespace) -> dict[str, Any]:
    repo = args.repo
    window = args.window
    closed_q = f"repo:{repo} is:pr is:closed is:unmerged created:{window}"
    merged_q = f"repo:{repo} is:pr is:merged created:{window}"

    closed_items = search_all_prs(closed_q, hard_cap=args.closed_cap)
    merged_items = search_all_prs(merged_q, hard_cap=args.merged_cap)

    index_prs: list[dict[str, Any]] = []
    cases: list[dict[str, Any]] = []

    # Keep synthetics
    if _e2e.CORPUS_PATH.exists():
        old = json.loads(_e2e.CORPUS_PATH.read_text(encoding="utf-8"))
        for c in old.get("cases", []):
            if c.get("source") == "fixture":
                cases.append(_e2e.normalize_case(c))

    print(f"Indexing {len(closed_items)} closed-unmerged…", flush=True)
    for i, item in enumerate(closed_items, 1):
        n = item["number"]
        print(f"  [{i}/{len(closed_items)}] closed #{n}", flush=True)
        try:
            meta = _e2e.fetch_pr_meta(repo, n)
            markers, per_file = _e2e.scan_pr_markers(repo, n)
        except Exception as e:  # noqa: BLE001
            print(f"    skip: {e}", flush=True)
            continue
        entry = {
            "pr": n,
            "repo": repo,
            "url": meta["html_url"],
            "title": meta["title"],
            "merged": False,
            "state": meta["state"],
            "author": meta.get("user"),
            "author_association": meta.get("author_association"),
            "created_at": meta.get("created_at"),
            "closed_at": meta.get("closed_at"),
            "additions": meta.get("additions"),
            "deletions": meta.get("deletions"),
            "changed_files": meta.get("changed_files"),
            "head_sha": meta.get("head_sha"),
            "base_sha": meta.get("base_sha"),
            "labels": meta.get("labels") or [],
            "discovery": {
                "markers_in_patch": markers,
                "marker_files": per_file,
                "scanned_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            },
            "cohort": "closed_unmerged",
        }
        index_prs.append(entry)
        if markers:
            label_class, expect_hard = "objective_slop", True
            rationale = f"Patch markers: {', '.join(markers)}"
        else:
            label_class, expect_hard = "closed_unmerged_sample", False
            rationale = (
                "Closed unmerged; no objective AIV hard markers in patches. "
                "Not labeled as AI authorship."
            )
        cases.append({
            "id": f"airflow-pr-{n}",
            "source": "github",
            "repo": repo,
            "pr": n,
            "url": meta["html_url"],
            "title": meta["title"],
            "state": meta["state"],
            "merged": False,
            "pr_meta": meta,
            "discovery": entry["discovery"],
            "provenance": {
                "origin": f"https://github.com/{repo}/pull/{n}",
                "head_sha": meta.get("head_sha"),
                "materialization": "mini-git + Contents API at head SHA",
                "config": "benchmarks/airflow/.aiv/",
            },
            "label": {
                "class": label_class,
                "expect_hard_fail": expect_hard,
                "expect_advisory": None,
                "rationale": rationale,
            },
            "expected": "ai_slop_positive" if expect_hard else "closed_unmerged_real",
            "notes": rationale,
        })
        time.sleep(0.12)

    print(f"Indexing {len(merged_items)} merged controls…", flush=True)
    for i, item in enumerate(merged_items, 1):
        n = item["number"]
        print(f"  [{i}/{len(merged_items)}] merged #{n}", flush=True)
        try:
            meta = _e2e.fetch_pr_meta(repo, n)
            markers, per_file = _e2e.scan_pr_markers(repo, n)
        except Exception as e:  # noqa: BLE001
            print(f"    skip: {e}", flush=True)
            continue
        entry = {
            "pr": n,
            "repo": repo,
            "url": meta["html_url"],
            "title": meta["title"],
            "merged": True,
            "state": meta["state"],
            "author": meta.get("user"),
            "author_association": meta.get("author_association"),
            "cohort": "merged_control",
            "discovery": {
                "markers_in_patch": markers,
                "marker_files": per_file,
                "scanned_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            },
        }
        index_prs.append(entry)
        cases.append({
            "id": f"airflow-pr-{n}",
            "source": "github",
            "repo": repo,
            "pr": n,
            "url": meta["html_url"],
            "title": meta["title"],
            "state": meta["state"],
            "merged": True,
            "pr_meta": meta,
            "discovery": entry["discovery"],
            "provenance": {
                "origin": f"https://github.com/{repo}/pull/{n}",
                "head_sha": meta.get("head_sha"),
                "materialization": "mini-git + Contents API at head SHA",
                "config": "benchmarks/airflow/.aiv/",
            },
            "label": {
                "class": "objective_slop" if markers else "merged_control",
                "expect_hard_fail": bool(markers),
                "expect_advisory": False,
                "rationale": (
                    f"Merged but markers {markers}" if markers
                    else "Merged control: hard fail = false positive."
                ),
            },
            "expected": "ai_slop_positive" if markers else "merged_real",
            "notes": entry["discovery"],
        })
        time.sleep(0.12)

    _e2e.ensure_fixture_cases(cases)

    index = {
        "schema_version": 1,
        "window": window,
        "repo": repo,
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "search": {
            "closed_unmerged_query": closed_q,
            "merged_query": merged_q,
            "closed_fetched": len(closed_items),
            "merged_fetched": len(merged_items),
        },
        "prs": index_prs,
    }
    CORPUS.mkdir(parents=True, exist_ok=True)
    (CORPUS / "census-index.json").write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")

    corpus = {
        "schema_version": 2,
        "repo_upstream": f"https://github.com/{repo}",
        "window": window,
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "methodology": _e2e.GATE_VALUE_NOTES and {
            "objective_slop": "PR patches matched AIV hard markers.",
            "merged_control": "Merged PR without those markers; hard fail = FP.",
            "closed_unmerged_sample": "Closed unmerged without markers — not AI-slop ground truth.",
            "not_claimed": "Closed-unmerged alone does not prove AI authorship.",
        },
        "stats": {
            "cases": len(cases),
            "objective_slop": sum(1 for c in cases if c.get("label", {}).get("class") == "objective_slop"),
            "merged_control": sum(1 for c in cases if c.get("label", {}).get("class") == "merged_control"),
            "closed_unmerged_sample": sum(
                1 for c in cases if c.get("label", {}).get("class") == "closed_unmerged_sample"
            ),
            "synthetic": sum(1 for c in cases if str(c.get("label", {}).get("class", "")).startswith("synthetic")),
        },
        "cases": cases,
    }
    (CORPUS / "cases-census.json").write_text(json.dumps(corpus, indent=2) + "\n", encoding="utf-8")
    # Also refresh primary cases.json used by e2e run if --replace-cases
    if args.replace_cases:
        _e2e.CORPUS_PATH.write_text(json.dumps(corpus, indent=2) + "\n", encoding="utf-8")
        print(f"Replaced {_e2e.CORPUS_PATH}", flush=True)
    print(f"Wrote census-index ({len(index_prs)} PRs) and cases-census ({len(cases)} cases)", flush=True)
    return corpus


def score_census(args: argparse.Namespace) -> dict[str, Any]:
    cases_path = CORPUS / "cases-census.json"
    if not cases_path.exists():
        raise SystemExit("Run index first (cases-census.json missing)")
    corpus = json.loads(cases_path.read_text(encoding="utf-8"))
    # Temporarily point e2e at census cases
    jar = _e2e.find_jar()
    materialize_mode = (os.environ.get("AIV_CENSUS_MATERIALIZE") or "head").strip().lower()
    cfg_override = (os.environ.get("AIV_CENSUS_AIV_CONFIG") or "").strip()
    if args.run_dir:
        run_dir = Path(args.run_dir)
        if not run_dir.is_absolute():
            run_dir = REPORTS / run_dir
    elif args.resume:
        existing = sorted(REPORTS.glob("census-*"), key=lambda p: p.name, reverse=True)
        run_dir = existing[0] if existing else REPORTS / f"census-{dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')}"
    else:
        stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        run_dir = REPORTS / f"census-{stamp}"
    run_dir.mkdir(parents=True, exist_ok=True)
    checkpoint = run_dir / "checkpoint.json"
    done: dict[str, Any] = {}
    if checkpoint.exists() and (args.resume or args.run_dir):
        done = {r["id"]: r for r in json.loads(checkpoint.read_text(encoding="utf-8")).get("cases", [])}

    rows: list[dict[str, Any]] = list(done.values())
    cases = [_e2e.normalize_case(c) for c in corpus.get("cases", [])]
    if args.limit:
        cases = cases[: args.limit]
    print(
        f"Scoring into {run_dir.name} materialize={materialize_mode} "
        f"config_override={cfg_override or '(default)'} checkpoint={len(done)}/{len(cases)}",
        flush=True,
    )

    for case in cases:
        cid = case["id"]
        if cid in done:
            print(f"skip {cid} (checkpoint)", flush=True)
            continue
        print(f"Scoring {cid}…", flush=True)
        case_dir = run_dir / cid
        case_dir.mkdir(parents=True, exist_ok=True)
        work = Path(tempfile.mkdtemp(prefix=f"aiv-census-{cid}-"))
        try:
            if case.get("source") == "fixture":
                base = _e2e.materialize_fixture(case, work)
            else:
                base = _e2e.materialize_pr_workspace(case["repo"], int(case["pr"]), work)
            # provenance dump
            prov = {
                "case_id": cid,
                "origin_url": case.get("url"),
                "repo": case.get("repo"),
                "pr": case.get("pr"),
                "title": case.get("title"),
                "label": case.get("label"),
                "discovery": case.get("discovery"),
                "pr_meta": case.get("pr_meta"),
                "materialize_mode": materialize_mode if case.get("source") != "fixture" else "fixture",
                "aiv_config_override": cfg_override or None,
                "workspace_files": sorted(
                    str(p.relative_to(work)).replace("\\", "/")
                    for p in work.rglob("*")
                    if p.is_file() and ".git" not in p.parts
                )[:200],
                "aiv_config_copied_from": "benchmarks/airflow/.aiv/",
                "materialized_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            }
            (case_dir / "provenance.json").write_text(json.dumps(prov, indent=2) + "\n", encoding="utf-8")
            out_json = case_dir / "aiv-report.json"
            report = _e2e.run_aiv(jar, work, base, out_json)
            outcome = _e2e.classify_outcome(case, report)
            row = {
                "id": cid,
                "source": case.get("source"),
                "url": case.get("url"),
                "title": case.get("title"),
                "pr": case.get("pr"),
                "repo": case.get("repo"),
                "label": case.get("label"),
                "discovery": case.get("discovery"),
                "pr_meta": case.get("pr_meta"),
                "provenance_path": str((case_dir / "provenance.json").relative_to(BENCH)).replace("\\", "/"),
                "aiv_report_path": str(out_json.relative_to(BENCH)).replace("\\", "/"),
                "outcome": outcome,
                "passed": report.get("passed"),
                "gates": report.get("gates") or [],
            }
            (case_dir / "result.json").write_text(json.dumps(row, indent=2) + "\n", encoding="utf-8")
            rows.append(row)
            done[cid] = row
            checkpoint.write_text(json.dumps({"cases": list(done.values())}, indent=2) + "\n", encoding="utf-8")
            print(f"  -> {outcome['verdict']} exit={outcome['exit_code']}", flush=True)
        except Exception as e:  # noqa: BLE001
            row = {
                "id": cid,
                "error": str(e),
                "url": case.get("url"),
                "label": case.get("label"),
                "outcome": {
                    "verdict": "ERROR",
                    "hard_fail": False,
                    "label_class": (case.get("label") or {}).get("class"),
                },
            }
            rows.append(row)
            done[cid] = row
            checkpoint.write_text(json.dumps({"cases": list(done.values())}, indent=2) + "\n", encoding="utf-8")
            print(f"  -> ERROR {e}", flush=True)
        finally:
            shutil.rmtree(work, ignore_errors=True)

    metrics = _e2e.compute_metrics(rows)
    claims = compute_claims(rows, metrics)
    summary = {
        "schema_version": 1,
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "aiv_jar": str(jar),
        "window": corpus.get("window"),
        "materialize_mode": materialize_mode,
        "aiv_config_override": cfg_override or None,
        "run_dir": str(run_dir.relative_to(BENCH)).replace("\\", "/"),
        "metrics": metrics,
        "claims": claims,
        "cases": rows,
        "gate_value_notes": _e2e.GATE_VALUE_NOTES,
        "third_party_validation": {
            "reproduce": [
                "python benchmarks/airflow/scripts/run-full-census.py index --closed-cap 1000 --merged-cap 30 --replace-cases",
                "set AIV_CENSUS_MATERIALIZE=patch",
                "set AIV_CENSUS_AIV_CONFIG=benchmarks/airflow/.aiv/config-census-patch.yaml",
                "python benchmarks/airflow/scripts/run-full-census.py score --resume",
                "open benchmarks/airflow/reports/census-latest.html",
            ],
            "bench_repo": "https://github.com/vaquarkhan/aiv-airflow-bench",
            "pr_urls": [r.get("url") for r in rows if r.get("url")],
        },
    }
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    write_census_html(summary, run_dir / "report.html")
    shutil.copyfile(run_dir / "report.html", REPORTS / "census-latest.html")
    (REPORTS / "census-latest.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(claims, indent=2), flush=True)
    return summary


def compute_claims(rows: list[dict[str, Any]], metrics: dict[str, Any]) -> dict[str, Any]:
    closed = [
        r for r in rows
        if (r.get("outcome") or {}).get("label_class") == "closed_unmerged_sample"
        or (r.get("label") or {}).get("class") == "closed_unmerged_sample"
        or (
            (r.get("outcome") or {}).get("label_class") == "objective_slop"
            and r.get("source") == "github"
            and not (r.get("pr_meta") or {}).get("merged")
        )
    ]
    # Prefer cohort: all github non-merged from this census
    closed_github = [
        r for r in rows
        if r.get("source") == "github" and not ((r.get("pr_meta") or {}).get("merged") or r.get("merged"))
    ]
    if not closed_github:
        closed_github = closed

    hard_blocked = [r for r in closed_github if (r.get("outcome") or {}).get("hard_fail")]
    n = len(closed_github)
    h = len(hard_blocked)
    block_rate = (h / n) if n else None

    # Gate breakdown for blocked
    from collections import Counter
    gate_c = Counter()
    for r in hard_blocked:
        for g in (r.get("outcome") or {}).get("blocking_gates") or []:
            gate_c[g] += 1

    verified = {
        "tag": "VERIFIED",
        "closed_unmerged_scored": n,
        "hard_blocked": h,
        "block_rate": block_rate,
        "block_rate_pct": (round(100 * block_rate, 2) if block_rate is not None else None),
        "blocking_gates": dict(gate_c),
        "merged_control_fp_rate": metrics.get("false_positive_rate_on_controls"),
        "synthetic_recall": metrics.get("hard_gate_recall_on_labeled_positives"),
        "statement": (
            f"Among {n} closed-unmerged Airflow PRs scored in this census, "
            f"AIV hard-failed {h} ({(100*block_rate):.2f}%)."
            if block_rate is not None else "No closed-unmerged scored."
        ),
    }

    estimated = {
        "tag": "ESTIMATED",
        "assumptions": [
            f"AIP-120 external closed-unmerged count N_ext={AIP120_EXTERNAL_CLOSED_UNMERGED} (wiki, same window).",
            f"Apply this census block_rate to N_ext (assumes similar defect rate on external-only subset).",
            f"Mean job-minutes per PR run M={AIP120_MEAN_JOB_MINUTES} from AIP-120 §3.2 sample (not a census).",
            "Assumes blocked PRs would otherwise have consumed ~M job-minutes of project CI once (order-of-magnitude).",
            "Does NOT claim those PRs are AI-generated.",
        ],
        "formula": {
            "estimated_blocked_of_508": "block_rate * 508",
            "estimated_job_minutes_avoided": "hard_blocked_ext * 779",
            "estimated_pct_of_508_ci": "block_rate * 100",
        },
        "estimated_blocked_of_508": (round(block_rate * AIP120_EXTERNAL_CLOSED_UNMERGED, 1) if block_rate is not None else None),
        "estimated_job_minutes_avoided_vs_mean": (
            round(block_rate * AIP120_EXTERNAL_CLOSED_UNMERGED * AIP120_MEAN_JOB_MINUTES, 0) if block_rate is not None else None
        ),
        "estimated_pct_of_external_closed_unmerged_blocked": (
            round(100 * block_rate, 2) if block_rate is not None else None
        ),
        "statement": (
            f"ESTIMATED: if the same {verified['block_rate_pct']}% hard-fail rate applied to AIP-120's "
            f"{AIP120_EXTERNAL_CLOSED_UNMERGED} external closed-unmerged PRs, ~"
            f"{round(block_rate * AIP120_EXTERNAL_CLOSED_UNMERGED, 1) if block_rate else 'n/a'} PRs "
            f"would be hard-blocked before full matrix; ~"
            f"{round(block_rate * AIP120_EXTERNAL_CLOSED_UNMERGED * AIP120_MEAN_JOB_MINUTES, 0) if block_rate else 'n/a'} "
            f"job-minutes at AIP-120 mean {AIP120_MEAN_JOB_MINUTES} min/PR. Not a measured CI bill."
            if block_rate is not None else "n/a"
        ),
    }

    not_claimed = [
        "That closed-unmerged PRs are AI-generated.",
        "Exact GitHub Actions dollars saved (Actions are free on public repos; capacity is concurrency/ASF shared).",
        "That issue-search ChatGPT counts equal AI-slop PRs.",
        "Full-file syntax gate results when materialize_mode=patch (syntax disabled in config-census-patch.yaml).",
    ]

    return {
        "verified_block_rate": verified,
        "estimated_ci_impact": estimated,
        "not_claimed": not_claimed,
        "aip120_reference_constants": {
            "external_closed_unmerged": AIP120_EXTERNAL_CLOSED_UNMERGED,
            "mean_job_minutes": AIP120_MEAN_JOB_MINUTES,
            "full_matrix_job_minutes": AIP120_FULL_MATRIX_JOB_MINUTES,
            "docs_job_minutes": AIP120_DOCS_JOB_MINUTES,
        },
    }


def write_census_html(summary: dict[str, Any], path: Path) -> None:
    claims = summary.get("claims") or {}
    v = claims.get("verified_block_rate") or {}
    e = claims.get("estimated_ci_impact") or {}
    rows = summary.get("cases") or []
    trs = []
    for r in rows:
        o = r.get("outcome") or {}
        url = r.get("url") or ""
        title = html.escape(r.get("title") or r.get("id") or "")
        link = f'<a href="{html.escape(url)}" target="_blank" rel="noopener">#{r.get("pr","")}</a> {title}' if url else title
        markers = ",".join((r.get("discovery") or {}).get("markers_in_patch") or [])
        gates = ", ".join(o.get("blocking_gates") or [])
        prov = html.escape(r.get("provenance_path") or "")
        trs.append(
            f"<tr class='v-{html.escape(o.get('verdict','?'))}'>"
            f"<td>{html.escape(r.get('id',''))}</td>"
            f"<td>{html.escape((r.get('label') or {}).get('class',''))}</td>"
            f"<td><span class='badge'>{html.escape(o.get('verdict','?'))}</span></td>"
            f"<td>{link}</td>"
            f"<td>{html.escape(markers)}</td>"
            f"<td>{html.escape(gates)}</td>"
            f"<td><code>{prov}</code></td></tr>"
        )

    doc = f"""<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"/>
<title>AIV Airflow full census</title>
<style>
body{{font-family:Segoe UI,system-ui,sans-serif;background:#0f1419;color:#e7ecf3;margin:0;padding:2rem}}
a{{color:#3d9cf0}} table{{width:100%;border-collapse:collapse;font-size:.85rem}}
th,td{{border-bottom:1px solid #2a3648;padding:.45rem;text-align:left;vertical-align:top}}
.card{{display:inline-block;background:#1a2332;border:1px solid #2a3648;border-radius:10px;padding:1rem;margin:.4rem}}
.badge{{padding:.1rem .4rem;border-radius:999px;background:#243044;font-weight:700;font-size:.75rem}}
.v-TP .badge,.v-TN .badge{{color:#3ecf8e}} .v-FP .badge,.v-ERROR .badge{{color:#f07178}}
.note{{color:#e6c07b}} code{{background:#0d1218;padding:.1rem .3rem;border-radius:4px}}
section{{background:#1a2332;border:1px solid #2a3648;border-radius:12px;padding:1rem;margin:1rem 0}}
</style></head><body>
<h1>AIV × Airflow closed-unmerged census</h1>
<p class="note">Generated {html.escape(summary.get('generated_at',''))}. Closed-unmerged ≠ AI authorship.</p>
<div>
  <div class="card"><div>VERIFIED block rate</div><strong>{html.escape(str(v.get('block_rate_pct')))}%</strong>
  <div>{v.get('hard_blocked')}/{v.get('closed_unmerged_scored')} hard-failed</div></div>
  <div class="card"><div>EST. of AIP-120 508</div><strong>~{html.escape(str(e.get('estimated_blocked_of_508')))}</strong>
  <div>PRs @ same rate</div></div>
  <div class="card"><div>EST. job-min avoided</div><strong>~{html.escape(str(e.get('estimated_job_minutes_avoided_vs_mean')))}</strong>
  <div>@ 779 min/PR mean</div></div>
  <div class="card"><div>Merged-control FP</div><strong>{html.escape(str((summary.get('metrics') or {}).get('false_positive_rate_on_controls')))}</strong></div>
</div>
<section><h2>Run metadata</h2>
<p>materialize_mode=<code>{html.escape(str(summary.get('materialize_mode')))}</code>
 · aiv_config_override=<code>{html.escape(str(summary.get('aiv_config_override')))}</code>
 · run_dir=<code>{html.escape(str(summary.get('run_dir')))}</code>
 · cases={len(rows)}</p></section>
<section><h2>VERIFIED statement</h2><p>{html.escape(v.get('statement',''))}</p>
<p>Blocking gates: {html.escape(json.dumps(v.get('blocking_gates') or {}))}</p></section>
<section><h2>ESTIMATED CI impact (not a measured bill)</h2>
<p>{html.escape(e.get('statement',''))}</p>
<ul>{''.join(f'<li>{html.escape(a)}</li>' for a in (e.get('assumptions') or []))}</ul></section>
<section><h2>NOT CLAIMED</h2><ul>{''.join(f'<li>{html.escape(x)}</li>' for x in (claims.get('not_claimed') or []))}</ul></section>
<section><h2>Every scored case (PR link + provenance path)</h2>
<table><thead><tr><th>ID</th><th>Label</th><th>Verdict</th><th>PR</th><th>Markers</th><th>Blocking</th><th>Provenance</th></tr></thead>
<tbody>{''.join(trs)}</tbody></table></section>
</body></html>"""
    path.write_text(doc, encoding="utf-8")


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="cmd", required=True)

    ix = sub.add_parser("index", help="Fetch all closed-unmerged (+ merged controls) and write census files")
    ix.add_argument("--repo", default="apache/airflow")
    ix.add_argument("--window", default=WINDOW_DEFAULT)
    ix.add_argument("--closed-cap", type=int, default=1000)
    ix.add_argument("--merged-cap", type=int, default=30)
    ix.add_argument("--replace-cases", action="store_true")

    sc = sub.add_parser("score", help="Score cases-census.json with aiv-cli")
    sc.add_argument("--limit", type=int, default=0)
    sc.add_argument("--resume", action="store_true", help="Resume latest census-* run (or --run-dir)")
    sc.add_argument("--run-dir", default="", help="Explicit reports/census-* directory")

    args = p.parse_args()
    if args.cmd == "index":
        build_index(args)
        return 0
    if args.cmd == "score":
        score_census(args)
        return 0
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
