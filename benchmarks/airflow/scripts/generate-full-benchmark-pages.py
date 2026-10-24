#!/usr/bin/env python3
"""Write the GitHub Pages benchmark from the full stored-patch AIV run.

Input:
  benchmarks/industry-e2e/results-full/rows.jsonl

Every stored prs/*.json file under the older bench corpus (2,098 files),
scored by the local aiv-cli 2.0 jar. The 15,575-row Airflow index has no
patch bytes, so it is reported as metadata only.
"""
from __future__ import annotations

import html
import json
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
FULL = ROOT / "benchmarks" / "industry-e2e" / "results-full" / "rows.jsonl"
OUT = ROOT / "docs" / "benchmark"
EXPECTED = 2098

LABELS = {
    "ai_disclosed": "AI disclosed",
    "merged_control": "Merged control",
    "closed_unmerged_sample": "Closed, not merged",
    "objective_slop": "Objective marker",
    "stored_not_in_555": "Extra stored patch, outside the labeled 555",
    "not_in_case_list": "Stored patch, outside the small case list",
    "stored_patch": "Stored patch",
}

RULES = {
    "test-adequacy.assertion-free-test": "Assertion-free test",
    "test-adequacy.missing-tests": "Missing tests",
    "design.forbidden.no-ai-generated-markers": "AI-generated marker in the diff",
    "syntax.parse": "Syntax error",
    "invariant.placeholder": "Placeholder text",
    "invariant.placeholder-test": "Placeholder test",
    "invariant.merge-conflict": "Merge-conflict marker",
    "invariant.ai-provenance": "Provenance trailer in the diff",
    "invariant.ai-edit-artifact": "Edit-artifact text",
}


def esc(v) -> str:
    return html.escape("" if v is None else str(v))


def label_name(row: dict) -> str:
    lab = row.get("label")
    if isinstance(lab, dict):
        return str(lab.get("class") or "unlabeled")
    return str(lab or "unlabeled")


def load_jsonl(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def tally(rows: list[dict]) -> dict:
    out = Counter()
    for r in rows:
        out[r.get("verdict") or ""] += 1
    return {
        "n": len(rows),
        "blocked": out["BLOCK"],
        "passed": out["PASS"],
        "unscored": out["UNSCORED"],
        "error": out["ERROR"],
    }


def primary(rules: list[str]) -> str:
    hard = [r for r in rules if r != "test-adequacy.missing-tests"]
    return hard[0] if hard else (rules[0] if rules else "unspecified")


CSS = r"""
:root { color-scheme: light; }
* { box-sizing: border-box; }
body { margin: 0; font-family: Georgia, "Times New Roman", serif; font-size: 18px; line-height: 1.6; color: #1b1f2a; background: #fff; }
header.site { background: #1f3a93; color: #fff; }
header.site .inner { max-width: 1080px; margin: 0 auto; padding: 18px 24px; display: flex; flex-wrap: wrap; align-items: center; gap: 14px 28px; }
header.site .brand { font-family: "Segoe UI", Arial, sans-serif; font-weight: 700; font-size: 20px; color: #fff; text-decoration: none; }
header.site .brand small { display: block; font-weight: 400; font-size: 13px; opacity: 0.85; }
nav.main { display: flex; flex-wrap: wrap; gap: 4px 6px; margin-left: auto; font-family: "Segoe UI", Arial, sans-serif; font-size: 15px; }
nav.main a { color: #fff; text-decoration: none; padding: 6px 10px; border-radius: 6px; }
nav.main a.current { background: #fff; color: #1f3a93; font-weight: 700; }
main { max-width: 1080px; margin: 0 auto; padding: 28px 24px 60px; }
h1, h2, h3 { font-family: "Segoe UI", Arial, sans-serif; line-height: 1.2; }
h1 { font-size: 40px; margin: 10px 0 8px; }
h2 { font-size: 28px; margin-top: 40px; border-bottom: 3px solid #d9480f; padding-bottom: 6px; }
p.lede { font-size: 22px; color: #4b5160; }
.kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin: 24px 0; }
.kpi { background: #f4f6fb; border: 1px solid #d7dce6; border-top: 6px solid #1f3a93; border-radius: 10px; padding: 16px 18px; }
.kpi.orange { border-top-color: #d9480f; }
.kpi.purple { border-top-color: #6b2fb3; }
.kpi .num { font-family: "Segoe UI", Arial, sans-serif; font-size: 34px; font-weight: 800; line-height: 1.1; }
.kpi .lbl { font-family: "Segoe UI", Arial, sans-serif; font-size: 15px; color: #4b5160; }
table { border-collapse: collapse; width: 100%; margin: 18px 0; font-family: "Segoe UI", Arial, sans-serif; font-size: 16px; }
th, td { border: 1px solid #d7dce6; padding: 9px 12px; text-align: left; vertical-align: top; }
th { background: #1f3a93; color: #fff; }
td.num, th.num { text-align: right; font-variant-numeric: tabular-nums; }
tbody tr:nth-child(even) { background: #f4f6fb; }
.callout { border-left: 8px solid #d9480f; background: #fff7f1; padding: 14px 20px; margin: 22px 0; }
a { color: #1f3a93; }
.muted { color: #4b5160; }
footer.site { max-width: 1080px; margin: 0 auto; padding: 18px 24px 40px; color: #4b5160; font-size: 14px; font-family: "Segoe UI", Arial, sans-serif; border-top: 1px solid #d7dce6; }
svg text { font-family: "Segoe UI", Arial, sans-serif; font-size: 14px; fill: #1b1f2a; }
"""

NAV = [
    ("index.html", "Overview"),
    ("dataset.html", "Dataset"),
    ("results.html", "Full run"),
    ("limits.html", "Limits"),
]


def page(title: str, current: str, body: str) -> str:
    links = []
    for href, name in NAV:
        cls = "current" if href == current else ""
        links.append(f'<a href="{href}" class="{cls}">{esc(name)}</a>')
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{esc(title)}</title>
<style>{CSS}</style>
</head>
<body>
<header class="site"><div class="inner">
<a class="brand" href="index.html">AIV Integrity Gate<small>Full stored-patch benchmark</small></a>
<nav class="main">{''.join(links)}</nav>
</div></header>
<main>
{body}
</main>
<footer class="site">Measured with local aiv-cli 2.0 built 2026-10-23 (working tree on ed1afa0 plus the uncommitted gate changes). Rows: benchmarks/industry-e2e/results-full/rows.jsonl. Policy is the Airflow bench .aiv tree, invariant gate set to fail. The 15,575-row index was not executed, because those records have no patch.</footer>
</body>
</html>
"""


def bars(pairs, color="#1f3a93"):
    width, label_w, row_h, gap = 720, 260, 32, 10
    height = max(len(pairs), 1) * (row_h + gap) + 8
    max_n = max((v for _, v in pairs), default=1) or 1
    parts = [f'<svg viewBox="0 0 {width} {height}" role="img">']
    for i, (name, val) in enumerate(pairs):
        y = 8 + i * (row_h + gap)
        w = (width - label_w - 70) * val / max_n
        parts.append(f'<text x="0" y="{y+18}">{esc(name)}</text>')
        parts.append(f'<rect x="{label_w}" y="{y}" width="{w:.1f}" height="24" rx="3" fill="{color}"/>')
        parts.append(f'<text x="{label_w + w + 8:.1f}" y="{y+18}">{val}</text>')
    parts.append("</svg>")
    return "\n".join(parts)


def comma(n: int) -> str:
    return f"{n:,}"


def table_rows(groups: list[tuple[str, list[dict]]]) -> str:
    lines = []
    for name, group in groups:
        t = tally(group)
        lines.append(
            f"<tr><td>{esc(name)}</td><td class='num'>{comma(t['n'])}</td><td class='num'>{comma(t['blocked'])}</td>"
            f"<td class='num'>{comma(t['passed'])}</td><td class='num'>{comma(t['unscored'])}</td>"
            f"<td class='num'>{comma(t['error'])}</td></tr>"
        )
    return "".join(lines)


def main():
    rows = load_jsonl(FULL)
    if len(rows) != EXPECTED:
        raise SystemExit(f"expected {EXPECTED} stored-patch rows in {FULL}, found {len(rows)}")
    for r in rows:
        r["_label"] = label_name(r)
        r["_primary"] = primary(r.get("blocking_rules") or [])
    air = [r for r in rows if r.get("project_repo") == "apache/airflow"]
    other = [r for r in rows if r.get("project_repo") != "apache/airflow"]
    air_t = tally(air)
    other_t = tally(other)
    all_t = tally(rows)
    unique = {(r.get("project_repo"), r.get("pr")) for r in rows}
    OUT.mkdir(parents=True, exist_ok=True)

    by_label = defaultdict(list)
    for r in air:
        by_label[r["_label"]].append(r)
    label_order = ["ai_disclosed", "merged_control", "closed_unmerged_sample", "objective_slop", "stored_patch"]
    label_order += [name for name in sorted(by_label) if name not in label_order]
    label_rows = table_rows([(LABELS.get(name, name), by_label.get(name, [])) for name in label_order if name in by_label])

    by_bench = defaultdict(list)
    for r in rows:
        by_bench[r.get("bench") or "?"].append(r)
    bench_rows = table_rows([(name, by_bench[name]) for name in sorted(by_bench)])

    by_repo = defaultdict(list)
    for r in other:
        by_repo[r.get("project_repo") or "?"].append(r)
    repo_rows = table_rows([(name, by_repo[name]) for name in sorted(by_repo)])

    blocked = [r for r in rows if r.get("verdict") == "BLOCK"]
    rule_counts = Counter(r["_primary"] for r in blocked)
    block_table = []
    for r in sorted(blocked, key=lambda x: (x.get("project_repo") or "", x.get("pr") or 0, x.get("id") or "")):
        rules = ", ".join(RULES.get(x, x) for x in dict.fromkeys(r.get("blocking_rules") or []))
        url = r.get("url") or ""
        link = f'<a href="{esc(url)}">{esc(r.get("pr"))}</a>' if url else esc(r.get("pr"))
        block_table.append(
            f"<tr><td>{esc(r.get('bench'))}</td><td>{esc(r.get('project_repo'))}</td>"
            f"<td>{esc(LABELS.get(r['_label'], r['_label']))}</td><td>{link}</td>"
            f"<td>{esc(r.get('title'))}</td><td>{esc(rules)}</td></tr>"
        )

    index = page("Full benchmark", "index.html", f"""
<h1>Full stored-patch run, not a sample</h1>
<p class="lede">AIV was executed on every stored patch file: {comma(all_t['n'])} files, {comma(len(unique))} unique pull requests. {comma(air_t['n'])} of the files are Apache Airflow. {comma(other_t['n'])} are from other projects.</p>
<div class="kpis">
<div class="kpi"><div class="num">{comma(all_t['n'])}</div><div class="lbl">stored patch files executed by AIV</div></div>
<div class="kpi orange"><div class="num">{comma(all_t['blocked'])}</div><div class="lbl">blocked by a gate finding</div></div>
<div class="kpi purple"><div class="num">{comma(all_t['passed'])}</div><div class="lbl">passed. {comma(all_t['unscored'])} could not be scored</div></div>
<div class="kpi"><div class="num">15,575</div><div class="lbl">Airflow rows indexed, not executed</div></div>
</div>
<div class="callout">
<h3>What was executed</h3>
<p>This is every <code>prs/*.json</code> file in the stored corpus: the Airflow year folder, the Airflow proof folder, and the other project folders. It is not the 555-case sample and not the 55-case fixture subset. The 15,575-row index has titles, bodies, and line counts only. It has no diff, so AIV cannot score it. A block is a gate finding. It is not a label that the pull request is AI slop. Unscored is not a pass.</p>
<p>Of these files, 1,605 had been scored earlier. Those verdicts are the same, except two Airflow files that were previously unscored because the base file could not be fetched. Those two, pull requests 61939 and 69095, are now blocked for an AI-generated marker. The other 493 files are the Airflow year folder, scored in this run. Eighteen Airflow pull requests are stored in both Airflow folders. Each of those pairs has the same verdict.</p>
</div>
<h2>Airflow stored patches</h2>
<p>Airflow files include the labeled proof set and the extra year folder. Where the same pull request is stored in both folders, both files were executed, so the Airflow file count can be higher than the unique pull-request count.</p>
<table>
<thead><tr><th>Label</th><th class="num">Files</th><th class="num">Blocked</th><th class="num">Passed</th><th class="num">Unscored</th><th class="num">Errors</th></tr></thead>
<tbody>{label_rows}</tbody>
</table>
<p class="muted">Airflow files: {comma(air_t['blocked'])} blocked, {comma(air_t['passed'])} passed, {comma(air_t['unscored'])} unscored, {comma(air_t['error'])} errors.</p>
<h2>Why a patch blocked</h2>
{bars([(RULES.get(k, k), v) for k, v in rule_counts.most_common()], "#d9480f")}
<p class="muted">One primary rule per blocked file. A missing-tests finding is not counted as the reason when a harder finding is on the same gate.</p>
""")

    dataset = page("Dataset", "dataset.html", f"""
<h1>Two layers were on disk. One was executed.</h1>
<table>
<thead><tr><th>Layer</th><th class="num">Rows</th><th>What AIV could do</th></tr></thead>
<tbody>
<tr><td>Airflow metadata index, one year</td><td class="num">15,575</td><td>Nothing. No patch is stored. Labels are not defect truth.</td></tr>
<tr><td>Every stored patch file</td><td class="num">{comma(all_t['n'])}</td><td>Executed. The patch was applied onto the file at the recorded base commit, then aiv-cli 2.0 scored the diff.</td></tr>
<tr><td>of which Apache Airflow files</td><td class="num">{comma(air_t['n'])}</td><td>Executed. Proof folder plus the year folder.</td></tr>
<tr><td>of which other project files</td><td class="num">{comma(other_t['n'])}</td><td>Executed. Every stored patch file in those folders.</td></tr>
<tr><td>Unique pull requests</td><td class="num">{comma(len(unique))}</td><td>Some Airflow pull requests are stored twice, once in each Airflow folder.</td></tr>
</tbody>
</table>
<div class="callout">
<p>Unscored means the stored patch could not be applied or had no file hunks. It is not a pass and not a block. A filled Generated-by line was not treated as a failure. Errors: {comma(all_t['error'])}.</p>
</div>
""")

    results = page("Full run", "results.html", f"""
<h1>Every executed folder</h1>
<table>
<thead><tr><th>Folder</th><th class="num">Files</th><th class="num">Blocked</th><th class="num">Passed</th><th class="num">Unscored</th><th class="num">Errors</th></tr></thead>
<tbody>{bench_rows}</tbody>
</table>
<h2>Airflow by label</h2>
<table>
<thead><tr><th>Label</th><th class="num">Files</th><th class="num">Blocked</th><th class="num">Passed</th><th class="num">Unscored</th><th class="num">Errors</th></tr></thead>
<tbody>{label_rows}</tbody>
</table>
<h2>Other repositories</h2>
<table>
<thead><tr><th>Repository</th><th class="num">Files</th><th class="num">Blocked</th><th class="num">Passed</th><th class="num">Unscored</th><th class="num">Errors</th></tr></thead>
<tbody>{repo_rows}</tbody>
</table>
<h2>Every blocked stored patch</h2>
<table>
<thead><tr><th>Folder</th><th>Repository</th><th>Label</th><th>PR</th><th>Title</th><th>Findings</th></tr></thead>
<tbody>{''.join(block_table)}</tbody>
</table>
""")

    limits = page("Limits", "limits.html", """
<h1>What these results do not say</h1>
<div class="callout">
<p>They do not say what share of real AI slop AIV would catch. Disclosure is not a defect. A long function is not unused code. Closed and not merged is not slop. The 15,575 index was not an AIV run.</p>
</div>
<h2>Measured limits</h2>
<ul>
<li>On the added lines, a new function of 80 lines or more appears in about 9 percent of AI-disclosed patches and about 11 percent of merged controls. A length block would fail both.</li>
<li>A new name that appears once in the diff appears in about 51 percent of disclosed patches and about 22 percent of merged controls. That is not unused code, because the caller can live in a file the pull request did not change.</li>
<li>Four Airflow scheduler checks already exist and stay at warn: start_date=datetime.now(), missing catchup=, a sensor with no timeout=, and an empty execute(). This corpus is mostly core and provider diffs, not DAG files, so those rules barely appear here.</li>
</ul>
<p>The small fixture suite remains 35 expected failures blocked and 15 expected passes clear. That is agreement with designed fixtures. It is not this full run.</p>
""")

    (OUT / "index.html").write_text(index, encoding="utf-8")
    (OUT / "dataset.html").write_text(dataset, encoding="utf-8")
    (OUT / "results.html").write_text(results, encoding="utf-8")
    (OUT / "limits.html").write_text(limits, encoding="utf-8")
    print(f"airflow {air_t} other {other_t} blocked_rows {len(blocked)} -> {OUT}")


if __name__ == "__main__":
    main()
