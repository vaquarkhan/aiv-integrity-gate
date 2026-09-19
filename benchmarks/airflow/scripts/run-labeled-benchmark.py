#!/usr/bin/env python3
"""
Score the 50-case labeled corpus and write product-value claims.

Primary proof path (precision / recall / FP). Airflow census is secondary.
"""
from __future__ import annotations

import argparse
import datetime as dt
import html
import json
import shutil
import sys
import tempfile
from pathlib import Path

from importlib.machinery import SourceFileLoader

_e2e = SourceFileLoader(
    "e2e",
    str(Path(__file__).resolve().parent / "run-e2e-benchmark.py"),
).load_module()

BENCH = _e2e.BENCH
REPORTS = BENCH / "reports"
CORPUS = BENCH / "corpus" / "cases-labeled-50.json"


def compute_labeled_claims(rows: list[dict]) -> dict:
    def cohort(cls: str | None = None, expect_hard: bool | None = None):
        out = []
        for r in rows:
            lab = r.get("label") or {}
            if cls is not None and lab.get("class") != cls:
                continue
            if expect_hard is not None and bool(lab.get("expect_hard_fail")) != expect_hard:
                continue
            out.append(r)
        return out

    positives = cohort(expect_hard=True)  # 30 slop + 5 mixed-hard = 35
    negatives = cohort(expect_hard=False)  # 5 proper + 5 cohesion + 5 soft = 15

    def count_verdict(rs, v):
        return sum(1 for r in rs if (r.get("outcome") or {}).get("verdict") == v)

    tp = count_verdict(positives, "TP")
    fn = count_verdict(positives, "FN")
    fp = count_verdict(negatives, "FP")
    tn = count_verdict(negatives, "TN")
    n_pos = len(positives)
    n_neg = len(negatives)
    recall = (tp / n_pos) if n_pos else None
    precision = (tp / (tp + fp)) if (tp + fp) else None
    fpr = (fp / n_neg) if n_neg else None
    block_rate_on_slop = (tp / n_pos) if n_pos else None

    advisory_expected = [r for r in rows if (r.get("label") or {}).get("expect_advisory")]
    advisory_hit = sum(1 for r in advisory_expected if (r.get("outcome") or {}).get("advisory"))

    return {
        "tag": "VERIFIED",
        "n_cases": len(rows),
        "positives_expect_fail": n_pos,
        "negatives_expect_pass": n_neg,
        "TP": tp,
        "FN": fn,
        "FP": fp,
        "TN": tn,
        "hard_gate_recall": recall,
        "hard_gate_precision": precision,
        "false_positive_rate": fpr,
        "block_rate_on_labeled_slop": block_rate_on_slop,
        "advisory_expected": len(advisory_expected),
        "advisory_hit": advisory_hit,
        "statement": (
            f"On the labeled 50-case bench: blocked {tp}/{n_pos} expected-fail cases "
            f"(recall {(100 * recall) if recall is not None else 'n/a':.1f}%), "
            f"FP {fp}/{n_neg} expected-pass "
            f"({(100 * fpr) if fpr is not None else 'n/a':.1f}%), "
            f"precision {(100 * precision) if precision is not None else 'n/a':.1f}%."
            if recall is not None
            else "No cases scored."
        ),
        "not_claimed": [
            "That this equals % of AIP-120's 508 external closed-unmerged PRs",
            "That unlabeled Airflow closed-unmerged volume is AI authorship",
            "Exact CI dollars saved",
        ],
    }


def write_html(summary: dict, path: Path) -> None:
    claims = summary.get("claims") or {}
    rows = summary.get("cases") or []
    trs = []
    for r in rows:
        o = r.get("outcome") or {}
        lab = r.get("label") or {}
        trs.append(
            "<tr>"
            f"<td>{html.escape(r.get('id',''))}</td>"
            f"<td>{html.escape(lab.get('class',''))}/{html.escape(lab.get('subtype',''))}</td>"
            f"<td>{html.escape(str(lab.get('expect_hard_fail')))}</td>"
            f"<td><b>{html.escape(o.get('verdict','?'))}</b></td>"
            f"<td>{html.escape(', '.join(o.get('blocking_gates') or []))}</td>"
            f"<td>{html.escape(', '.join(o.get('advisory_gates') or []))}</td>"
            f"<td>{html.escape(r.get('title',''))}</td>"
            "</tr>"
        )
    doc = f"""<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"/>
<title>AIV labeled 50-case product bench</title>
<style>
body{{font-family:Segoe UI,system-ui,sans-serif;background:#0f1419;color:#e7ecf3;margin:0;padding:2rem}}
.card{{display:inline-block;background:#1a2332;border:1px solid #2a3648;border-radius:10px;padding:1rem;margin:.4rem;min-width:9rem}}
table{{width:100%;border-collapse:collapse;font-size:.85rem}} th,td{{border-bottom:1px solid #2a3648;padding:.4rem;text-align:left}}
.note{{color:#e6c07b}} section{{background:#1a2332;border:1px solid #2a3648;border-radius:12px;padding:1rem;margin:1rem 0}}
</style></head><body>
<h1>Labeled 50-case product bench</h1>
<p class="note">Primary proof for product value. Generated {html.escape(summary.get('generated_at',''))}.</p>
<div>
  <div class="card"><div>Recall (block slop)</div><strong>{html.escape(str(round(100*(claims.get('hard_gate_recall') or 0),1)))}%</strong>
  <div>{claims.get('TP')}/{claims.get('positives_expect_fail')} TP</div></div>
  <div class="card"><div>FP rate</div><strong>{html.escape(str(round(100*(claims.get('false_positive_rate') or 0),1)))}%</strong>
  <div>{claims.get('FP')}/{claims.get('negatives_expect_pass')} FP</div></div>
  <div class="card"><div>Precision</div><strong>{html.escape(str(round(100*(claims.get('hard_gate_precision') or 0),1)))}%</strong></div>
  <div class="card"><div>FN</div><strong>{claims.get('FN')}</strong></div>
</div>
<section><h2>VERIFIED statement</h2><p>{html.escape(claims.get('statement',''))}</p></section>
<section><h2>NOT CLAIMED</h2><ul>{''.join(f'<li>{html.escape(x)}</li>' for x in (claims.get('not_claimed') or []))}</ul></section>
<section><h2>All 50 cases</h2>
<table><thead><tr><th>ID</th><th>Label</th><th>Expect fail</th><th>Verdict</th><th>Blocking</th><th>Advisory</th><th>Title</th></tr></thead>
<tbody>{''.join(trs)}</tbody></table></section>
</body></html>"""
    path.write_text(doc, encoding="utf-8")


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--limit", type=int, default=0)
    args = p.parse_args()
    if not CORPUS.exists():
        raise SystemExit(f"Missing {CORPUS}; run generate-labeled-corpus.py first")

    # Point e2e at labeled corpus
    _e2e.CORPUS_PATH = CORPUS
    corpus = json.loads(CORPUS.read_text(encoding="utf-8"))
    jar = _e2e.find_jar()
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    run_dir = REPORTS / f"labeled-{stamp}"
    run_dir.mkdir(parents=True, exist_ok=True)

    rows = []
    cases = [_e2e.normalize_case(c) for c in corpus.get("cases", [])]
    # preserve inject_tree / paths
    by_id = {c["id"]: c for c in corpus.get("cases", [])}
    if args.limit:
        cases = cases[: args.limit]

    for case in cases:
        raw = by_id.get(case["id"], {})
        case = {**case, **{k: raw[k] for k in ("paths", "inject_tree") if k in raw}}
        cid = case["id"]
        print(f"Scoring {cid}…", flush=True)
        case_dir = run_dir / cid
        case_dir.mkdir(parents=True, exist_ok=True)
        work = Path(tempfile.mkdtemp(prefix=f"aiv-lab-{cid}-"))
        try:
            base = _e2e.materialize_fixture(case, work)
            out_json = case_dir / "aiv-report.json"
            report = _e2e.run_aiv(jar, work, base, out_json)
            outcome = _e2e.classify_outcome(case, report)
            row = {
                "id": cid,
                "title": case.get("title") or raw.get("title"),
                "label": case.get("label"),
                "outcome": outcome,
                "passed": report.get("passed"),
                "gates": report.get("gates") or [],
                "aiv_report_path": str(out_json.relative_to(BENCH)).replace("\\", "/"),
            }
            (case_dir / "result.json").write_text(json.dumps(row, indent=2) + "\n", encoding="utf-8")
            rows.append(row)
            print(
                f"  -> {outcome['verdict']} hard={outcome['hard_fail']} "
                f"adv={outcome.get('advisory')} block={outcome.get('blocking_gates')}",
                flush=True,
            )
        except Exception as e:  # noqa: BLE001
            rows.append({"id": cid, "error": str(e), "label": case.get("label"),
                         "outcome": {"verdict": "ERROR", "hard_fail": False}})
            print(f"  -> ERROR {e}", flush=True)
        finally:
            shutil.rmtree(work, ignore_errors=True)

    claims = compute_labeled_claims(rows)
    summary = {
        "schema_version": 1,
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "aiv_jar": str(jar),
        "corpus": str(CORPUS.relative_to(_e2e.ROOT)).replace("\\", "/"),
        "claims": claims,
        "metrics": _e2e.compute_metrics(rows),
        "cases": rows,
        "claim_policy": corpus.get("claim_policy"),
    }
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    write_html(summary, run_dir / "report.html")
    shutil.copyfile(run_dir / "report.html", REPORTS / "labeled-latest.html")
    (REPORTS / "labeled-latest.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(claims, indent=2), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
