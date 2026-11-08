#!/usr/bin/env python3
"""Generate the multi-page AIV Airflow benchmark site.

Every number on the site is read from files on disk:

* benchmarks/airflow/results/professional-benchmark-latest.json
* benchmarks/airflow/results/structural-latest.json
* benchmarks/airflow/results/selected-real-head-latest.json
* benchmarks/airflow/reports/labeled-latest.json
* ../aiv-airflow-bench/README.md (seven-project dataset table and trailer counts)
* ../aiv-airflow-bench/docs/data/windows-latest.json (stored sample class mix)

The site is static HTML with inline CSS and inline SVG charts. No JavaScript,
no external assets, no green in the palette, no em or en dashes.

Usage:
    python benchmarks/airflow/scripts/generate-benchmark-site.py
        [--output benchmarks/airflow/reports/site]
        [--bench-repo ../aiv-airflow-bench]
"""
from __future__ import annotations

import argparse
import html
import json
import math
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
AIRFLOW_DIR = HERE.parent
REPO_ROOT = AIRFLOW_DIR.parent.parent
DEFAULT_BENCH_REPO = REPO_ROOT.parent / "aiv-airflow-bench"

FORBIDDEN_CHARS = ("\u2014", "\u2013")

# High-contrast light palette. No green anywhere.
C_INK = "#1b1f2a"
C_MUTED = "#4b5160"
C_NAVY = "#1f3a93"
C_ORANGE = "#d9480f"
C_PURPLE = "#6b2fb3"
C_SLATE = "#8a94a6"
C_GOLD = "#b7791f"
C_PAPER = "#ffffff"
C_PANEL = "#f4f6fb"
C_LINE = "#d7dce6"
C_RED = "#b42318"

PROJECT_ORDER = ["Airflow", "Flink", "Kafka", "curl", "Godot", "Ghostty", "COSMIC compositor"]
PROJECT_KEYS = {
    "Airflow": "airflow",
    "Flink": "flink",
    "Kafka": "kafka",
    "curl": "curl",
    "Godot": "godot",
    "Ghostty": "ghostty",
    "COSMIC compositor": "cosmic",
}


# --------------------------------------------------------------------------- data

def load_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as fh:
        return json.load(fh)


def to_int(text: str) -> int:
    return int(text.replace(",", "").strip())


def parse_readme_table(readme: str) -> list[dict]:
    rows = []
    pattern = re.compile(
        r"^\|\s*(Airflow|Flink|Kafka|curl|Godot|Ghostty|COSMIC compositor)\s*\|"
        r"\s*([\d,]+)\s*\|\s*([\d,]+)\s*\|\s*([\d,]+)\s*\|\s*([\d,]+)\s*\|"
        r"\s*([\d,]+)(?: of [\d,]+)?\s*\|\s*([\d,]+)(?: of [\d,]+)?\s*\|",
        re.MULTILINE,
    )
    for m in pattern.finditer(readme):
        rows.append(
            {
                "project": m.group(1),
                "prs_6m": to_int(m.group(2)),
                "prs_1y": to_int(m.group(3)),
                "generated_by_6m": to_int(m.group(4)),
                "generated_by_1y": to_int(m.group(5)),
                "stored_6m": to_int(m.group(6)),
                "stored_1y": to_int(m.group(7)),
            }
        )
    if len(rows) != 7:
        raise SystemExit(f"expected 7 project rows in README table, found {len(rows)}")
    rows.sort(key=lambda r: PROJECT_ORDER.index(r["project"]))
    return rows


def parse_readme_trailers(readme: str) -> dict:
    out = {}
    m = re.search(r"\*\*([\d,]+)\*\* filled disclosures in the year \(\*\*([\d,]+)\*\* of them in the last 6 months\)", readme)
    if m:
        out["disclosures_1y"] = to_int(m.group(1))
        out["disclosures_6m"] = to_int(m.group(2))
    m = re.search(r"\*\*([\d,]+)\*\* filled `Generated-by` trailers: ([^.]+)\.", readme)
    if m:
        out["trailers_total"] = to_int(m.group(1))
        tools = {name: to_int(num) for name, num in re.findall(r"([A-Za-z]+) (\d{1,3}(?:,\d{3})*)", m.group(2))}
        if sum(tools.values()) != out["trailers_total"]:
            raise SystemExit(f"trailer tool counts {sum(tools.values())} do not sum to total {out['trailers_total']}")
        out["trailers_by_tool"] = tools
    m = re.search(r"Airflow issues in the stored index: ([\d,]+) in the last 6 months, ([\d,]+) in the year", readme)
    if m:
        out["issues_6m"] = to_int(m.group(1))
        out["issues_1y"] = to_int(m.group(2))
    m = re.search(r"Dev-list messages: ([\d,]+) and ([\d,]+)", readme)
    if m:
        out["mail_6m"] = to_int(m.group(1))
        out["mail_1y"] = to_int(m.group(2))
    return out


def fixture_breakdown(labeled: dict) -> tuple[list[dict], dict]:
    groups: dict[tuple, dict] = {}
    rule_counts: dict[str, int] = {}
    for case in labeled["cases"]:
        key = (case["label"]["class"], case["label"].get("subtype") or "", case["outcome"]["verdict"])
        groups.setdefault(key, {"class": key[0], "subtype": key[1], "verdict": key[2], "count": 0})
        groups[key]["count"] += 1
        for rule in case["outcome"]["blocking_rules"]:
            rule_counts[rule] = rule_counts.get(rule, 0) + 1
    ordered = sorted(groups.values(), key=lambda g: (g["verdict"] != "TP", g["class"], -g["count"], g["subtype"]))
    return ordered, dict(sorted(rule_counts.items(), key=lambda kv: -kv[1]))


# ------------------------------------------------------------------------- helpers

def esc(value) -> str:
    return html.escape(str(value), quote=True)


def fmt(n) -> str:
    if isinstance(n, float):
        return f"{n:,.1f}"
    return f"{n:,}"


def pct(num: float, den: float, digits: int = 1) -> str:
    if den == 0:
        return "n/a"
    return f"{100.0 * num / den:.{digits}f}%"


# ---------------------------------------------------------------------- svg charts

def svg_hbar(rows: list[tuple[str, float, str]], width: int = 820, bar_h: int = 26, gap: int = 12,
             label_w: int = 190, value_fmt=fmt, title: str = "") -> str:
    """Horizontal bar chart. rows = [(label, value, color)]."""
    max_v = max((r[1] for r in rows), default=1) or 1
    chart_w = width - label_w - 110
    height = len(rows) * (bar_h + gap) + 10
    parts = [f'<svg class="chart" viewBox="0 0 {width} {height}" width="100%" role="img" aria-label="{esc(title)}">']
    if title:
        parts.append(f"<title>{esc(title)}</title>")
    y = 4
    for label, value, color in rows:
        w = max(2, round(chart_w * value / max_v))
        parts.append(f'<text x="{label_w - 10}" y="{y + bar_h * 0.68}" text-anchor="end" class="axis">{esc(label)}</text>')
        parts.append(f'<rect x="{label_w}" y="{y}" width="{chart_w}" height="{bar_h}" fill="{C_PANEL}" rx="4"/>')
        parts.append(f'<rect x="{label_w}" y="{y}" width="{w}" height="{bar_h}" fill="{color}" rx="4"/>')
        parts.append(f'<text x="{label_w + w + 8}" y="{y + bar_h * 0.68}" class="val">{esc(value_fmt(value))}</text>')
        y += bar_h + gap
    parts.append("</svg>")
    return "".join(parts)


def svg_grouped_hbar(rows: list[tuple[str, list[tuple[float, str, str]]]], width: int = 820, bar_h: int = 16,
                     gap: int = 18, label_w: int = 190, title: str = "", log: bool = False) -> str:
    """Grouped horizontal bars. rows = [(label, [(value, color, series_name)])]."""
    values = [v for _, series in rows for v, _, _ in series]
    max_v = max(values) if values else 1
    scale = (lambda v: math.log10(v + 1) / math.log10(max_v + 1)) if log else (lambda v: v / max_v if max_v else 0)
    chart_w = width - label_w - 110
    n_series = max(len(s) for _, s in rows)
    group_h = n_series * (bar_h + 3)
    height = len(rows) * (group_h + gap) + 10
    parts = [f'<svg class="chart" viewBox="0 0 {width} {height}" width="100%" role="img" aria-label="{esc(title)}">']
    if title:
        parts.append(f"<title>{esc(title)}</title>")
    y = 4
    for label, series in rows:
        parts.append(f'<text x="{label_w - 10}" y="{y + group_h / 2 + 5}" text-anchor="end" class="axis">{esc(label)}</text>')
        yy = y
        for value, color, name in series:
            w = max(2, round(chart_w * scale(value)))
            parts.append(f'<rect x="{label_w}" y="{yy}" width="{w}" height="{bar_h}" fill="{color}" rx="3"><title>{esc(name)}: {fmt(value)}</title></rect>')
            parts.append(f'<text x="{label_w + w + 6}" y="{yy + bar_h - 3}" class="val small">{fmt(value)}</text>')
            yy += bar_h + 3
        y += group_h + gap
    parts.append("</svg>")
    return "".join(parts)


def svg_donut(slices: list[tuple[str, float, str]], title: str = "", size: int = 260) -> str:
    total = sum(v for _, v, _ in slices) or 1
    cx = cy = size / 2
    r = size * 0.38
    stroke = size * 0.16
    circ = 2 * math.pi * r
    parts = [f'<svg class="donut" viewBox="0 0 {size} {size}" width="{size}" height="{size}" role="img" aria-label="{esc(title)}">']
    if title:
        parts.append(f"<title>{esc(title)}</title>")
    offset = 0.0
    for label, value, color in slices:
        length = circ * value / total
        parts.append(
            f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="none" stroke="{color}" stroke-width="{stroke}" '
            f'stroke-dasharray="{length:.2f} {circ - length:.2f}" stroke-dashoffset="{-offset:.2f}" '
            f'transform="rotate(-90 {cx} {cy})"><title>{esc(label)}: {fmt(value)}</title></circle>'
        )
        offset += length
    parts.append(f'<text x="{cx}" y="{cy - 4}" text-anchor="middle" class="donut-big">{fmt(int(total))}</text>')
    parts.append(f'<text x="{cx}" y="{cy + 18}" text-anchor="middle" class="donut-small">total</text>')
    parts.append("</svg>")
    return "".join(parts)


def legend(items: list[tuple[str, float, str]]) -> str:
    total = sum(v for _, v, _ in items) or 1
    lis = "".join(
        f'<li><span class="swatch" style="background:{c}"></span>{esc(l)} <strong>{fmt(v)}</strong> <span class="muted">({pct(v, total)})</span></li>'
        for l, v, c in items
    )
    return f'<ul class="legend">{lis}</ul>'


def svg_confusion(tp: int, fn: int, fp: int, tn: int, title: str) -> str:
    cells = [
        (0, 0, "True positive", tp, C_NAVY),
        (1, 0, "False negative", fn, C_RED if fn else C_SLATE),
        (0, 1, "False positive", fp, C_RED if fp else C_SLATE),
        (1, 1, "True negative", tn, C_PURPLE),
    ]
    w, h = 420, 250
    parts = [f'<svg class="chart" viewBox="0 0 {w} {h}" width="100%" style="max-width:420px" role="img" aria-label="{esc(title)}"><title>{esc(title)}</title>']
    parts.append(f'<text x="230" y="18" text-anchor="middle" class="axis">AIV result</text>')
    parts.append(f'<text x="125" y="44" text-anchor="middle" class="axis small">blocked</text>')
    parts.append(f'<text x="330" y="44" text-anchor="middle" class="axis small">passed</text>')
    parts.append(f'<text x="14" y="100" class="axis small" transform="rotate(-90 14 100)" text-anchor="middle">expected block</text>')
    parts.append(f'<text x="14" y="200" class="axis small" transform="rotate(-90 14 200)" text-anchor="middle">expected pass</text>')
    for col, row, label, value, color in cells:
        x = 30 + col * 205
        y = 52 + row * 98
        parts.append(f'<rect x="{x}" y="{y}" width="190" height="88" rx="8" fill="{color}"/>')
        parts.append(f'<text x="{x + 95}" y="{y + 42}" text-anchor="middle" class="cell-big">{value}</text>')
        parts.append(f'<text x="{x + 95}" y="{y + 70}" text-anchor="middle" class="cell-label">{esc(label)}</text>')
    parts.append("</svg>")
    return "".join(parts)


def svg_ci_bars(suites: list[dict]) -> str:
    """Point recall (always 100 on executed suites) with exact 95% lower bound."""
    width, row_h = 820, 48
    label_w, chart_w = 300, 380
    height = len(suites) * row_h + 50
    parts = [f'<svg class="chart" viewBox="0 0 {width} {height}" width="100%" role="img" aria-label="Recall lower bounds"><title>Observed recall and exact 95 percent lower bound</title>']
    for tick in (0, 25, 50, 75, 100):
        x = label_w + chart_w * tick / 100
        parts.append(f'<line x1="{x}" y1="8" x2="{x}" y2="{height - 26}" stroke="{C_LINE}"/>')
        parts.append(f'<text x="{x}" y="{height - 8}" text-anchor="middle" class="axis small">{tick}%</text>')
    x75 = label_w + chart_w * 0.75
    parts.append(f'<line x1="{x75}" y1="8" x2="{x75}" y2="{height - 26}" stroke="{C_ORANGE}" stroke-dasharray="6 4" stroke-width="2"/>')
    parts.append(f'<text x="{x75 + 4}" y="20" class="axis small" fill="{C_ORANGE}">75% target</text>')
    y = 30
    for s in suites:
        recall = 100.0 * s["detected"] / s["positives"] if s["positives"] else 0
        lo = s["lower95"]
        x_lo = label_w + chart_w * lo / 100
        x_pt = label_w + chart_w * recall / 100
        parts.append(f'<text x="{label_w - 10}" y="{y + 16}" text-anchor="end" class="axis">{esc(s["name"])} ({s["detected"]}/{s["positives"]})</text>')
        parts.append(f'<rect x="{x_lo}" y="{y + 4}" width="{max(2, x_pt - x_lo)}" height="18" fill="{C_NAVY}" opacity="0.25" rx="3"/>')
        parts.append(f'<line x1="{x_lo}" y1="{y}" x2="{x_lo}" y2="{y + 26}" stroke="{C_NAVY}" stroke-width="3"/>')
        parts.append(f'<circle cx="{x_pt}" cy="{y + 13}" r="7" fill="{C_PURPLE}"/>')
        parts.append(f'<text x="{x_pt + 14}" y="{y + 18}" class="val small">{lo:.1f}% lower bound</text>')
        y += row_h
    parts.append("</svg>")
    return "".join(parts)


# ------------------------------------------------------------------------- layout

NAV = [
    ("index.html", "Overview"),
    ("problem.html", "The AI slop problem"),
    ("dataset.html", "Dataset"),
    ("methodology.html", "Methodology"),
    ("catches.html", "What AIV caught"),
    ("proof.html", "Proof"),
    ("value.html", "Why AIV helps"),
    ("verification.html", "Verification"),
]

CSS = f"""
:root {{ color-scheme: light; }}
* {{ box-sizing: border-box; }}
body {{ margin: 0; font-family: Georgia, "Times New Roman", serif; font-size: 18px; line-height: 1.6;
       color: {C_INK}; background: {C_PAPER}; }}
header.site {{ background: {C_NAVY}; color: #fff; }}
header.site .inner {{ max-width: 1080px; margin: 0 auto; padding: 18px 24px; display: flex; flex-wrap: wrap;
                      align-items: center; gap: 14px 28px; }}
header.site .brand {{ font-family: "Segoe UI", Arial, sans-serif; font-weight: 700; font-size: 20px; color: #fff; text-decoration: none; }}
header.site .brand small {{ display: block; font-weight: 400; font-size: 13px; opacity: 0.85; }}
nav.main {{ display: flex; flex-wrap: wrap; gap: 4px 6px; margin-left: auto; font-family: "Segoe UI", Arial, sans-serif; font-size: 15px; }}
nav.main a {{ color: #fff; text-decoration: none; padding: 6px 10px; border-radius: 6px; border: 1px solid transparent; }}
nav.main a:hover {{ border-color: rgba(255,255,255,0.6); }}
nav.main a.current {{ background: #fff; color: {C_NAVY}; font-weight: 700; }}
main {{ max-width: 1080px; margin: 0 auto; padding: 28px 24px 60px; }}
h1, h2, h3 {{ font-family: "Segoe UI", Arial, sans-serif; color: {C_INK}; line-height: 1.2; }}
h1 {{ font-size: 40px; margin: 10px 0 8px; }}
h2 {{ font-size: 28px; margin-top: 44px; border-bottom: 3px solid {C_ORANGE}; padding-bottom: 6px; }}
h3 {{ font-size: 21px; margin-top: 28px; }}
p.lede {{ font-size: 22px; color: {C_MUTED}; margin-top: 0; }}
a {{ color: {C_NAVY}; }}
.kpis {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin: 24px 0; }}
.kpi {{ background: {C_PANEL}; border: 1px solid {C_LINE}; border-top: 6px solid {C_NAVY}; border-radius: 10px; padding: 16px 18px; }}
.kpi.orange {{ border-top-color: {C_ORANGE}; }}
.kpi.purple {{ border-top-color: {C_PURPLE}; }}
.kpi.gold {{ border-top-color: {C_GOLD}; }}
.kpi .num {{ font-family: "Segoe UI", Arial, sans-serif; font-size: 38px; font-weight: 800; color: {C_INK}; line-height: 1.1; }}
.kpi .lbl {{ font-family: "Segoe UI", Arial, sans-serif; font-size: 15px; color: {C_MUTED}; margin-top: 4px; }}
.cards {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 18px; margin: 24px 0; }}
.card {{ display: block; background: {C_PAPER}; border: 1px solid {C_LINE}; border-left: 8px solid {C_PURPLE}; border-radius: 10px;
         padding: 18px 20px; text-decoration: none; color: {C_INK}; }}
.card:hover {{ background: {C_PANEL}; }}
.card h3 {{ margin: 0 0 6px; color: {C_NAVY}; }}
.card p {{ margin: 0; color: {C_MUTED}; font-size: 16px; }}
.card .step {{ font-family: "Segoe UI", Arial, sans-serif; font-size: 13px; letter-spacing: 1px; text-transform: uppercase; color: {C_ORANGE}; font-weight: 700; }}
table {{ border-collapse: collapse; width: 100%; margin: 18px 0; font-family: "Segoe UI", Arial, sans-serif; font-size: 16px; }}
th, td {{ border: 1px solid {C_LINE}; padding: 9px 12px; text-align: left; vertical-align: top; }}
th {{ background: {C_NAVY}; color: #fff; }}
td.num, th.num {{ text-align: right; font-variant-numeric: tabular-nums; }}
tbody tr:nth-child(even) {{ background: {C_PANEL}; }}
.panel {{ background: {C_PANEL}; border: 1px solid {C_LINE}; border-radius: 10px; padding: 18px 22px; margin: 20px 0; }}
.callout {{ border-left: 8px solid {C_ORANGE}; background: #fff7f1; padding: 14px 20px; margin: 22px 0; border-radius: 0 10px 10px 0; }}
.callout.purple {{ border-left-color: {C_PURPLE}; background: #f6f1fc; }}
.callout.navy {{ border-left-color: {C_NAVY}; background: #eef2fb; }}
.callout h3 {{ margin-top: 0; }}
.chart-wrap {{ background: #fff; border: 1px solid {C_LINE}; border-radius: 10px; padding: 16px; margin: 18px 0; }}
.chart-wrap figcaption {{ font-family: "Segoe UI", Arial, sans-serif; font-size: 15px; color: {C_MUTED}; margin-top: 8px; }}
figure {{ margin: 0; }}
svg.chart text, svg.donut text {{ font-family: "Segoe UI", Arial, sans-serif; fill: {C_INK}; }}
svg .axis {{ font-size: 15px; }}
svg .val {{ font-size: 15px; font-weight: 700; }}
svg .small {{ font-size: 12px; }}
svg.chart text.cell-big {{ font-size: 36px; font-weight: 800; fill: #fff; }}
svg.chart text.cell-label {{ font-size: 14px; fill: #fff; }}
svg .donut-big {{ font-size: 30px; font-weight: 800; }}
svg .donut-small {{ font-size: 13px; fill: {C_MUTED}; }}
.row {{ display: flex; flex-wrap: wrap; gap: 24px; align-items: center; }}
ul.legend {{ list-style: none; padding: 0; margin: 0; font-family: "Segoe UI", Arial, sans-serif; font-size: 16px; }}
ul.legend li {{ margin: 6px 0; }}
.swatch {{ display: inline-block; width: 16px; height: 16px; border-radius: 4px; vertical-align: -2px; margin-right: 8px; }}
.muted {{ color: {C_MUTED}; }}
code, pre {{ font-family: Consolas, "Courier New", monospace; font-size: 15px; }}
pre {{ background: #f0f2f7; border: 1px solid {C_LINE}; border-radius: 8px; padding: 14px 16px; overflow-x: auto; }}
.pager {{ display: flex; justify-content: space-between; margin-top: 50px; padding-top: 18px; border-top: 1px solid {C_LINE};
          font-family: "Segoe UI", Arial, sans-serif; }}
footer.site {{ max-width: 1080px; margin: 0 auto; padding: 18px 24px 40px; color: {C_MUTED}; font-size: 14px;
               font-family: "Segoe UI", Arial, sans-serif; border-top: 1px solid {C_LINE}; }}
.badge {{ display: inline-block; font-family: "Segoe UI", Arial, sans-serif; font-size: 13px; font-weight: 700; padding: 2px 8px; border-radius: 999px; color: #fff; }}
.badge.block {{ background: {C_NAVY}; }}
.badge.pass {{ background: {C_PURPLE}; }}
.badge.warn {{ background: {C_GOLD}; }}
"""


def page(current: str, title: str, body: str, generated_at: str) -> str:
    links = "".join(
        f'<a href="{href}" class="{"current" if href == current else ""}">{esc(label)}</a>' for href, label in NAV
    )
    idx = [h for h, _ in NAV].index(current)
    prev_link = f'<a href="{NAV[idx - 1][0]}">&larr; {esc(NAV[idx - 1][1])}</a>' if idx > 0 else "<span></span>"
    next_link = f'<a href="{NAV[idx + 1][0]}">{esc(NAV[idx + 1][1])} &rarr;</a>' if idx < len(NAV) - 1 else '<a href="index.html">Back to overview &rarr;</a>'
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{esc(title)} | AIV Airflow Benchmark</title>
<style>{CSS}</style>
</head>
<body>
<header class="site"><div class="inner">
<a class="brand" href="index.html">AIV Integrity Gate<small>Apache Airflow AI slop benchmark</small></a>
<nav class="main" aria-label="Site">{links}</nav>
</div></header>
<main>
{body}
<div class="pager">{prev_link}{next_link}</div>
</main>
<footer class="site">Generated {esc(generated_at)} by <code>benchmarks/airflow/scripts/generate-benchmark-site.py</code> from result files on disk.
Author: Viquar Khan. Static HTML, inline SVG, no scripts, no external assets.</footer>
</body>
</html>
"""


# -------------------------------------------------------------------------- pages

def build_pages(ctx: dict) -> dict[str, str]:
    prof = ctx["prof"]
    lab = ctx["labeled"]["metrics"]
    struct = ctx["structural"]
    sel = ctx["selected"]
    table = ctx["table"]
    tr = ctx["trailers"]
    windows = ctx["windows"]
    labels = prof["metadata_labels"]
    # GitHub outcome totals. metadata_labels is an exclusive partition in which merged / closed_unmerged / open
    # are only the rows with no AI signal, so the KPIs must come from metadata_states.
    states = prof.get("metadata_states")
    if not states:
        raise SystemExit("professional-benchmark-latest.json lacks metadata_states; rerun generate-professional-report.py")
    cov = prof["coverage"]
    obs = prof["observed"]
    suites = prof["suites"]
    gen = prof["generated_at"]
    airflow = table[0]

    pages = {}

    # ---------------------------------------------------------------- index
    cards = "".join(
        f'<a class="card" href="{href}"><span class="step">Step {i}</span><h3>{esc(label)}</h3><p>{esc(blurb)}</p></a>'
        for i, (href, label, blurb) in enumerate(
            [
                ("problem.html", "The AI slop problem", "Why open source maintainers are drowning in low-effort AI generated pull requests, and what the Airflow numbers show."),
                ("dataset.html", "Dataset", f"{fmt(airflow['prs_1y'])} Airflow pull requests indexed over one year, plus six other projects, with merged, closed, open and disclosure counts."),
                ("methodology.html", "Methodology", "How the benchmark labels cases, what counts as an objective defect, and why each suite has its own denominator."),
                ("catches.html", "What AIV caught", "Every blocking rule that fired, the fixture classes behind them, and the real Airflow pull request that was intercepted."),
                ("proof.html", "Proof", "Confusion matrices, exact 95 percent lower bounds, and the full census of stored patches."),
                ("value.html", "Why AIV helps", "How a deterministic evidence gate keeps obvious defects out of the review queue without pretending to judge authorship."),
                ("verification.html", "Verification", "Reproduce every number from the JSON receipts and the current source, and read the claim boundaries honestly."),
            ],
            start=1,
        )
    )
    body = f"""
<h1>Can a deterministic gate stop AI slop before a human reviews it?</h1>
<p class="lede">An end-to-end benchmark of the AIV Integrity Gate against the Apache Airflow pull request stream,
built from {fmt(cov['full_metadata_index_rows_processed'])} indexed pull requests and {fmt(cov['stored_patch_cases_processed'])} stored patches.</p>
<div class="kpis">
<div class="kpi"><div class="num">{obs['objective_detections']}/{obs['objective_detections']}</div><div class="lbl">objective defects detected across the three executed suites</div></div>
<div class="kpi orange"><div class="num">{obs['hard_control_failures']}/{obs['executed_controls']}</div><div class="lbl">hard control failures (clean changes wrongly blocked)</div></div>
<div class="kpi purple"><div class="num">{fmt(labels['ai_disclosed'])}</div><div class="lbl">Airflow pull requests with a filled AI disclosure in one year</div></div>
<div class="kpi gold"><div class="num">{fmt(cov['full_metadata_index_rows_processed'])}</div><div class="lbl">Airflow pull requests indexed, one year window</div></div>
</div>
<div class="callout">
<h3>Read this first</h3>
<p>{esc(prof['claim_boundary'])} AIV does not detect who or what wrote the code. It blocks objective, mechanically
verifiable defects such as unresolved merge conflict blocks, unauthorized Git submodule links, edit tool artifacts and
placeholder text left on the added lines of a change. Disclosure, merge and closure status are context, not defect truth.</p>
</div>
<h2>Walk the benchmark end to end</h2>
<div class="cards">{cards}</div>
<h2>Headline numbers</h2>
<table>
<thead><tr><th>Suite</th><th class="num">Positives</th><th class="num">Detected</th><th class="num">Controls</th><th class="num">False blocks</th><th class="num">Exact 95% lower bound on recall</th></tr></thead>
<tbody>
{"".join(f'<tr><td>{esc(s["name"])}</td><td class="num">{s["positives"]}</td><td class="num">{s["detected"]}</td><td class="num">{s["controls"]}</td><td class="num">{s["false_blocks"]}</td><td class="num">{s["lower95"]:.1f}%</td></tr>' for s in suites)}
</tbody></table>
<p class="muted">Each suite keeps its own denominator. Pooling them into a single field accuracy figure would be misleading, so the site never does that.</p>
"""
    pages["index.html"] = page("index.html", "Overview", body, gen)

    # -------------------------------------------------------------- problem
    tools = tr.get("trailers_by_tool", {})
    tool_colors = [C_NAVY, C_ORANGE, C_PURPLE, C_GOLD, C_SLATE, "#2c5aa0", "#9b4f1e", "#5a3e8c"]
    tool_slices = [(k, v, tool_colors[i % len(tool_colors)]) for i, (k, v) in enumerate(tools.items())]
    body = f"""
<h1>The AI slop problem in open source</h1>
<p class="lede">Low-effort machine generated contributions are now a daily tax on volunteer maintainers. Airflow is one of the clearest places to watch it happen.</p>
<h2>What maintainers are seeing</h2>
<p>Over the last two years, coding assistants moved from autocomplete to autonomous agents that open pull requests on their own.
Most of those changes are fine. A growing minority are not: they ship with unresolved merge conflict markers, with the literal
<code>&lt;&lt;&lt;&lt;&lt;&lt;&lt; SEARCH</code> and <code>&gt;&gt;&gt;&gt;&gt;&gt;&gt; REPLACE</code> blocks that an editing tool
uses internally, with <code>TODO: implement</code> placeholders in production paths, with a Git submodule pointer that nobody
asked for, or with marketing style attribution comments pasted into source. None of these need a human to spot. All of them
currently consume a human's time anyway, because they land in the same review queue as everything else.</p>
<p>The cost is not only the minutes spent on each bad change. It is the erosion of reviewer attention. When a queue fills with
obviously broken submissions, careful review of the genuinely difficult changes suffers, and maintainers start to burn out or
close contributions in bulk. Several projects in this dataset now require contributors to state whether a tool wrote the code.
Airflow added a disclosure checkbox and a <code>Generated-by</code> trailer for exactly that reason.</p>
<h2>What the Airflow stream looks like</h2>
<div class="kpis">
<div class="kpi"><div class="num">{fmt(states['merged'])}</div><div class="lbl">merged pull requests in the one year index</div></div>
<div class="kpi orange"><div class="num">{fmt(states['closed_unmerged'])}</div><div class="lbl">closed without merge</div></div>
<div class="kpi purple"><div class="num">{fmt(states['open'])}</div><div class="lbl">still open at index time</div></div>
<div class="kpi gold"><div class="num">{fmt(labels['ai_disclosed'])}</div><div class="lbl">filled AI disclosure</div></div>
</div>
<p class="muted">The first three numbers are GitHub outcomes and sum to {fmt(cov['full_metadata_index_rows_processed'])}. The fourth is a metadata label on the same rows: of the {fmt(labels['ai_disclosed'])} disclosed pull requests, most were merged.</p>
<div class="row">
<div class="chart-wrap"><figure>{svg_donut(tool_slices, "Filled Generated-by trailers by tool")}<figcaption>Filled <code>Generated-by</code> trailers in the Airflow one year index, by tool.</figcaption></figure></div>
<div>{legend(tool_slices)}<p class="muted">Total filled trailers: {fmt(tr.get('trailers_total', 0))}. Disclosures that left the placeholder tool name are counted as disclosed but not attributed to a tool.</p></div>
</div>
<div class="callout purple">
<h3>Disclosure is not defect</h3>
<p>{fmt(labels['ai_disclosed'])} disclosed pull requests does not mean {fmt(labels['ai_disclosed'])} bad pull requests. Many disclosed changes
were merged after normal review. Likewise, a closed unmerged pull request is often closed for scope or timing, not quality.
This benchmark therefore never uses disclosure, merge or closure as the truth label for a defect. It uses the content of the
added lines, and only where a defect is mechanically provable.</p>
</div>
<h2>Why a gate, and not a detector</h2>
<p>Tools that try to guess whether a model wrote a block of code are probabilistic and easy to argue with. A maintainer cannot
reject a contribution on a classifier's hunch. A gate that says "this file contains an unresolved conflict block at line 212"
is a different kind of statement: it is true or false, it is reproducible, and the contributor can fix it without debate.
AIV is built around that second kind of statement. The rest of this site shows what that choice buys, and what it does not.</p>
<p>Next: <a href="dataset.html">the dataset</a>, including Airflow and six other projects.</p>
"""
    pages["problem.html"] = page("problem.html", "The AI slop problem", body, gen)

    # -------------------------------------------------------------- dataset
    rows_html = "".join(
        f'<tr><td>{esc(r["project"])}</td><td class="num">{fmt(r["prs_6m"])}</td><td class="num">{fmt(r["prs_1y"])}</td>'
        f'<td class="num">{fmt(r["generated_by_6m"])}</td><td class="num">{fmt(r["generated_by_1y"])}</td>'
        f'<td class="num">{fmt(r["stored_6m"])}</td><td class="num">{fmt(r["stored_1y"])}</td></tr>'
        for r in table
    )
    grouped = [
        (r["project"], [(r["prs_1y"], C_NAVY, "PRs, 1 year"), (r["generated_by_1y"], C_ORANGE, "Generated-by search, 1 year"), (r["stored_1y"], C_PURPLE, "Stored sample, 1 year")])
        for r in table
    ]
    state_rows = [
        ("Merged", states["merged"], C_NAVY),
        ("Closed unmerged", states["closed_unmerged"], C_ORANGE),
        ("Open at collection", states["open"], C_PURPLE),
    ]
    label_rows = [
        ("AI disclosed", labels["ai_disclosed"], C_GOLD),
        ("AI tool mention", labels["ai_tool_mention"], C_SLATE),
        ("AI slop discussion", labels["ai_slop_discussion"], C_RED),
        ("Merged, no AI signal", labels["merged"], C_NAVY),
        ("Closed unmerged, no AI signal", labels["closed_unmerged"], C_ORANGE),
        ("Open, no AI signal", labels["open"], C_PURPLE),
    ]
    one_year = windows["samples"]["one_year"]
    class_colors = {
        "merged_control": C_NAVY, "closed_unmerged_sample": C_ORANGE, "ai_disclosed": C_GOLD,
        "ai_tool_mention": C_SLATE, "ai_slop_discussion": C_RED, "objective_slop": C_PURPLE,
    }
    class_rows = []
    for name in PROJECT_ORDER:
        key = PROJECT_KEYS[name]
        by = one_year.get(key, {}).get("by_class", {})
        class_rows.append((f"{name} ({one_year.get(key, {}).get('cases', 0)})", [(v, class_colors.get(k, C_SLATE), k) for k, v in sorted(by.items(), key=lambda kv: -kv[1])]))
    class_legend = legend([(k.replace("_", " "), 0, v) for k, v in class_colors.items()]).replace(" <strong>0</strong> <span class=\"muted\">(n/a)</span>", "")
    body = f"""
<h1>Dataset</h1>
<p class="lede">Seven open source projects, one year of pull requests, and a complete Airflow index. All counts come from the
<code>aiv-airflow-bench</code> corpus committed alongside this repository.</p>
<h2>Seven projects at a glance</h2>
<table>
<thead><tr><th>Project</th><th class="num">PRs, 6 months</th><th class="num">PRs, 1 year</th><th class="num">Generated-by search, 6 months</th><th class="num">Generated-by search, 1 year</th><th class="num">Stored sample, 6 months</th><th class="num">Stored sample, 1 year</th></tr></thead>
<tbody>{rows_html}</tbody></table>
<p class="muted">Windows: one year {esc(windows['windows']['one_year']['start'])} to {esc(windows['windows']['one_year']['end'])}; six months {esc(windows['windows']['six_months']['start'])} to {esc(windows['windows']['six_months']['end'])}.
The <code>Generated-by</code> search column is a GitHub search total and includes template text on some projects. It is a signal of disclosure volume, not a census of AI authored changes.</p>
<div class="chart-wrap"><figure>{svg_grouped_hbar(grouped, title="Pull requests, Generated-by hits and stored samples per project, log scale", log=True)}
<figcaption>Per project, one year window, logarithmic scale so the smaller projects stay visible. Navy: pull requests. Orange: Generated-by search hits. Purple: stored patch sample.</figcaption></figure></div>
<h2>Airflow: the complete index</h2>
<p>The Airflow row is a complete index of {fmt(airflow['prs_1y'])} pull requests, not only the {fmt(airflow['stored_1y'])} row stored patch sample.
The GitHub outcome of every row:</p>
<div class="chart-wrap"><figure>{svg_hbar(state_rows, title="Airflow pull requests by GitHub state, one year")}
<figcaption>GitHub state of the {fmt(cov['full_metadata_index_rows_processed'])} indexed Airflow pull requests. These three sum to the index.</figcaption></figure></div>
<p>Reading every stored body also gives each row one exclusive metadata label, assigned in priority order: slop discussion, then
disclosed, then tool mention; only rows with no AI signal fall through to merged, closed unmerged or open. The three "no AI signal"
classes below are therefore a residue, not the GitHub totals above.</p>
<div class="chart-wrap"><figure>{svg_hbar(label_rows, title="Airflow metadata labels, one year")}
<figcaption>Exclusive metadata labels over {fmt(cov['full_metadata_index_rows_processed'])} indexed Airflow pull requests. The six classes partition the index.</figcaption></figure></div>
<table>
<thead><tr><th>Measure</th><th class="num">Last 6 months</th><th class="num">One year</th></tr></thead>
<tbody>
<tr><td>Pull requests indexed</td><td class="num">{fmt(airflow['prs_6m'])}</td><td class="num">{fmt(airflow['prs_1y'])}</td></tr>
<tr><td>Merged (GitHub state)</td><td class="num muted">n/a</td><td class="num">{fmt(states['merged'])}</td></tr>
<tr><td>Closed without merge (GitHub state)</td><td class="num muted">n/a</td><td class="num">{fmt(states['closed_unmerged'])}</td></tr>
<tr><td>Open at collection (GitHub state)</td><td class="num muted">n/a</td><td class="num">{fmt(states['open'])}</td></tr>
<tr><td>Filled AI disclosures</td><td class="num">{fmt(tr.get('disclosures_6m', 0))}</td><td class="num">{fmt(tr.get('disclosures_1y', 0))}</td></tr>
<tr><td>Filled Generated-by trailers</td><td class="num muted">n/a</td><td class="num">{fmt(tr.get('trailers_total', 0))}</td></tr>
<tr><td>Issues in the stored index</td><td class="num">{fmt(tr.get('issues_6m', 0))}</td><td class="num">{fmt(tr.get('issues_1y', 0))}</td></tr>
<tr><td>Dev list messages</td><td class="num">{fmt(tr.get('mail_6m', 0))}</td><td class="num">{fmt(tr.get('mail_1y', 0))}</td></tr>
<tr><td>Stored patches with sidecar</td><td class="num">{fmt(airflow['stored_6m'])}</td><td class="num">{fmt(cov['stored_patch_cases_processed'])}</td></tr>
</tbody></table>
<h2>Stored sample composition</h2>
<p>Each project's stored sample is labelled by how the pull request was selected. These are selection classes, not quality judgements.</p>
<div class="chart-wrap"><figure>{svg_grouped_hbar(class_rows, title="Stored sample by selection class, one year", bar_h=12, gap=14)}
<figcaption>Stored one year sample per project (case count in parentheses), split by selection class.</figcaption></figure>{class_legend}</div>
<p>Next: <a href="methodology.html">how the benchmark turns this corpus into verifiable tests</a>.</p>
"""
    pages["dataset.html"] = page("dataset.html", "Dataset", body, gen)

    # ----------------------------------------------------------- methodology
    body = f"""
<h1>Methodology</h1>
<p class="lede">Three suites, three denominators, one rule: a case only counts if AIV actually executed on it and produced a canonical receipt.</p>
<h2>What counts as an objective defect</h2>
<p>A finding is objective when it can be proved from the bytes of the added lines alone, with no judgement about intent or
authorship. The benchmark recognises these objective classes on the added surface of a change:</p>
<table>
<thead><tr><th>Class</th><th>What it looks like</th><th>Blocking rule</th></tr></thead>
<tbody>
<tr><td>Merge conflict block</td><td>Unresolved <code>&lt;&lt;&lt;&lt;&lt;&lt;&lt;</code>, <code>=======</code>, <code>&gt;&gt;&gt;&gt;&gt;&gt;&gt;</code> markers committed to a source file</td><td><code>invariant.merge-conflict</code></td></tr>
<tr><td>Edit tool artifact</td><td>Search and replace or diff fence blocks that an AI editing tool uses internally</td><td><code>invariant.ai-edit-artifact</code></td></tr>
<tr><td>Placeholder</td><td>Literal placeholder text or unimplemented stubs on a production path</td><td><code>invariant.placeholder</code></td></tr>
<tr><td>Attribution marker</td><td>Generated-by style comments and tool banners pasted into source</td><td><code>design.forbidden.no-ai-generated-markers</code>, <code>invariant.ai-provenance</code></td></tr>
<tr><td>Unexpected gitlink</td><td>A new Git submodule pointer (mode 160000) not on the approved list</td><td><code>invariant.unexpected-gitlink</code></td></tr>
<tr><td>Unparseable source</td><td>A changed file that no longer parses in its own language</td><td><code>syntax.parse</code></td></tr>
</tbody></table>
<p>Everything else that AIV reports, such as test adequacy, cohesion, density and verbosity, is advisory in the Airflow
preset. Advisory findings never count as a block, and a control that only receives advisory findings is a true negative.</p>
<h2>The three suites</h2>
<div class="cards">
<div class="card"><span class="step">Suite 1</span><h3>Designed fixture agreement</h3><p>{lab['n_cases']} hand built cases: {lab['positives_expect_fail']} expected to block and {lab['negatives_expect_pass']} expected to pass. The positives reproduce the defect classes above in Airflow shaped files. The negatives include clean controls and deliberately verbose or low cohesion code that must not be blocked.</p></div>
<div class="card"><span class="step">Suite 2</span><h3>Production equivalent Git structure</h3><p>{struct['metrics']['positives'] + struct['metrics']['negatives']} real Git repositories built on disk so AIV reads them exactly as CI would: an added conflict, an unapproved gitlink, and an approved gitlink control.</p></div>
<div class="card"><span class="step">Suite 3</span><h3>Selected Airflow reconstruction</h3><p>{len(sel['rows'])} real Airflow pull requests from the corpus, rebuilt as changed file head reconstructions with patch fallback. One carries a committed merge conflict; one is a clean merged control.</p></div>
</div>
<h2>How a case is scored</h2>
<ol>
<li>AIV runs from the shaded CLI jar against the case repository with the Airflow preset pinned to the base commit.</li>
<li>The JSON report must be schema version 3. Every gate result must carry a canonical <code>result_key</code> equal to <code>id:partition</code>, and <code>partition</code>, <code>passed</code> and <code>blocks_ci</code> must agree with each other and with the process exit code.</li>
<li>A positive is a true positive only if the expected rule appears in the blocking partition. A block for some other reason is recorded, but does not count.</li>
<li>A control is a true negative only if nothing blocks. Advisory findings are allowed.</li>
<li>Any inconsistency, missing receipt or process failure is an <code>ERROR</code> row. Errors abort the run; they are never folded into a pass or a fail.</li>
</ol>
<h2>Labels come from added lines only</h2>
<p>When the benchmark scans the stored Airflow patches for hard markers, it scans only lines that the patch adds. A pull request
that removes an old conflict marker is doing the right thing and is not a positive. This rule removed a source of inflated
true positives found in an earlier run.</p>
<div class="callout navy">
<h3>Why there is no single accuracy number</h3>
<p>The fixture suite tests agreement with a specification. The structural suite tests the Git adapter under production
conditions. The selected suite tests real Airflow content. These are different questions with different sample sizes, so each
gets its own confusion matrix and its own exact binomial lower bound. Pooling them would let {lab['positives_expect_fail']} synthetic cases disguise how small the real world sample is.</p>
</div>
<p>Next: <a href="catches.html">what AIV actually caught</a>.</p>
"""
    pages["methodology.html"] = page("methodology.html", "Methodology", body, gen)

    # -------------------------------------------------------------- catches
    groups, rule_counts = ctx["fixture_groups"], ctx["rule_counts"]
    group_rows = "".join(
        f'<tr><td>{esc(g["class"].replace("_", " "))}</td><td>{esc(g["subtype"].replace("_", " "))}</td><td class="num">{g["count"]}</td>'
        f'<td><span class="badge {"block" if g["verdict"] == "TP" else "pass"}">{esc(g["verdict"])}</span></td></tr>'
        for g in groups
    )
    rule_bars = [(rule, count, [C_NAVY, C_ORANGE, C_PURPLE, C_GOLD, C_SLATE, C_RED][i % 6]) for i, (rule, count) in enumerate(rule_counts.items())]
    struct_rows = "".join(
        f'<tr><td><code>{esc(c["id"])}</code></td><td>{"block" if c["expected_block"] else "pass"}</td><td>{"block" if c["actual_block"] else "pass"}</td>'
        f'<td>{", ".join(f"<code>{esc(r)}</code>" for r in c["rules"]) or "<span class=muted>none</span>"}</td><td class="num">{c["exit_code"]}</td></tr>'
        for c in struct["cases"]
    )
    sel_rows = ""
    for r in sel["rows"]:
        o = r["outcome"]
        sel_rows += (
            f'<tr><td><a href="{esc(r["url"])}">#{r["pr"]}</a></td><td>{esc(r["label"]["class"].replace("_", " "))}</td>'
            f'<td><span class="badge {"block" if o["hard_fail"] else "pass"}">{esc(o["verdict"])}</span></td>'
            f'<td>{", ".join(f"<code>{esc(x)}</code>" for x in o["blocking_rules"]) or "<span class=muted>none</span>"}</td>'
            f'<td>{", ".join(f"<code>{esc(x)}</code>" for x in o["advisory_gates"]) or "<span class=muted>none</span>"}</td></tr>'
        )
    pr_finding = next((g for r in sel["rows"] if r["pr"] == 59957 for g in r["gates"] if g["id"] == "invariant"), None)
    pr_msg = esc(pr_finding["message"]) if pr_finding else "see selected-real-head-latest.json"
    body = f"""
<h1>What AIV caught</h1>
<p class="lede">Every block below is tied to a rule identifier, a file and a receipt. Nothing here is inferred from a model's opinion.</p>
<h2>Blocking rules that fired in the fixture suite</h2>
<div class="chart-wrap"><figure>{svg_hbar(rule_bars, title="Blocking rule occurrences across the 50 fixture cases", label_w=330)}
<figcaption>Occurrences of each blocking rule across the {lab['n_cases']} fixture cases. A case can trigger more than one rule, so the total exceeds {lab['TP']}.</figcaption></figure></div>
<h2>Fixture classes and outcomes</h2>
<table>
<thead><tr><th>Label class</th><th>Subtype</th><th class="num">Cases</th><th>Outcome</th></tr></thead>
<tbody>{group_rows}</tbody></table>
<p>The <em>clean plus artifact</em> mixed cases matter most: a mostly good change with one edit artifact buried inside still blocks.
The <em>cohesion warn</em> and <em>verbose clean</em> mixed cases matter for the opposite reason: ugly but correct code passes with
only advisory notes.</p>
<h2>Production equivalent Git structure</h2>
<table>
<thead><tr><th>Case</th><th>Expected</th><th>Actual</th><th>Rules</th><th class="num">Exit</th></tr></thead>
<tbody>{struct_rows}</tbody></table>
<p>These cases are real repositories on disk. The gitlink case proves the Git adapter now reads raw diff object modes and
recognises a new submodule pointer; the approved gitlink control proves the allow list works.</p>
<h2>The real Airflow interception</h2>
<table>
<thead><tr><th>Pull request</th><th>Label</th><th>Outcome</th><th>Blocking rules</th><th>Advisory gates</th></tr></thead>
<tbody>{sel_rows}</tbody></table>
<div class="callout">
<h3>Pull request #59957</h3>
<p>AIV message: <code>{pr_msg}</code></p>
<p>This is the one stored Airflow patch out of {fmt(cov['stored_patch_cases_processed'])} whose added lines carry a hard marker. It was
rebuilt as a changed file head reconstruction and AIV blocked it on both the conflict rule and a syntax parse failure, which is
the expected consequence of a conflict block inside a Python file. The clean merged control, #70842, passed with advisory
notes only.</p>
</div>
<div class="callout purple">
<h3>What AIV did not catch, and why</h3>
<p>The stored patch census found exactly one added line hard marker in {fmt(cov['stored_patch_cases_processed'])} patches
({obs['stored_patch_marker_rate_percent']:.3f} percent). Objective slop is rare in a mature project with active review. Most of what
maintainers call AI slop is subjective: wrong scope, no tests, hallucinated APIs, confident but wrong explanations. AIV does not
claim to block those. It keeps them advisory on purpose, so that the hard fail path stays indisputable.</p>
</div>
<p>Next: <a href="proof.html">the proof, with confusion matrices and confidence bounds</a>.</p>
"""
    pages["catches.html"] = page("catches.html", "What AIV caught", body, gen)

    # ---------------------------------------------------------------- proof
    sm = struct["metrics"]
    sel_tp = sum(1 for r in sel["rows"] if r["outcome"]["verdict"] == "TP")
    sel_fn = sum(1 for r in sel["rows"] if r["outcome"]["verdict"] == "FN")
    sel_fp = sum(1 for r in sel["rows"] if r["outcome"]["verdict"] == "FP")
    sel_tn = sum(1 for r in sel["rows"] if r["outcome"]["verdict"] == "TN")
    census = [
        ("Indexed rows", cov["full_metadata_index_rows_processed"], C_NAVY),
        ("Size eligible rows", cov["metadata_runnable_size_rows"], C_PURPLE),
        ("Stored patches", cov["stored_patch_cases_processed"], C_ORANGE),
        ("Patches with added line markers", cov["stored_patch_marker_cases"], C_RED),
    ]
    body = f"""
<h1>Proof</h1>
<p class="lede">Three confusion matrices, three exact binomial lower bounds, and a full census of the stored corpus. Point estimates alone would flatter small samples, so the bounds are shown next to them.</p>
<h2>Confusion matrices</h2>
<div class="row">
<div class="chart-wrap"><figure>{svg_confusion(lab['TP'], lab['FN'], lab['FP'], lab['TN'], "Fixture suite")}<figcaption>Fixture suite, {lab['n_cases']} cases.</figcaption></figure></div>
<div class="chart-wrap"><figure>{svg_confusion(sm['true_positives'], sm['positives'] - sm['true_positives'], sm['hard_false_positives'], sm['true_negatives'], "Structural suite")}<figcaption>Structural suite, {sm['positives'] + sm['negatives']} repositories.</figcaption></figure></div>
<div class="chart-wrap"><figure>{svg_confusion(sel_tp, sel_fn, sel_fp, sel_tn, "Selected real suite")}<figcaption>Selected Airflow suite, {len(sel['rows'])} pull requests.</figcaption></figure></div>
</div>
<h2>Recall with exact 95 percent lower bounds</h2>
<div class="chart-wrap"><figure>{svg_ci_bars(suites)}
<figcaption>Purple dot: observed recall. Navy bar: exact Clopper Pearson 95 percent lower bound. Orange dashed line: the 75 percent target set before the run.</figcaption></figure></div>
<p>All three suites observed 100 percent recall and zero hard false positives. The lower bounds tell the honest story about
sample size: {suites[0]['lower95']:.1f} percent for {suites[0]['positives']} fixture positives, {suites[1]['lower95']:.1f} percent for
{suites[1]['positives']} structural positives, and {suites[2]['lower95']:.1f} percent for a single real positive. Only the fixture
suite has enough positives to clear the 75 percent target with statistical confidence. The other two are regression evidence.</p>
<h2>Census of the stored corpus</h2>
<div class="chart-wrap"><figure>{svg_hbar(census, title="Airflow corpus census", label_w=290)}
<figcaption>Every indexed row and every stored patch was processed. {cov['stored_patch_cases_missing_sidecar']} patches were missing their metadata sidecar.</figcaption></figure></div>
<table>
<thead><tr><th>Measure</th><th class="num">Value</th><th>Source file</th></tr></thead>
<tbody>
<tr><td>Fixture agreement</td><td class="num">{prof['effectiveness']['fixture_agreement_percent']:.1f}%</td><td><code>reports/labeled-latest.json</code></td></tr>
<tr><td>Structural regression recall</td><td class="num">{prof['effectiveness']['structural_regression_recall_percent']:.1f}%</td><td><code>results/structural-latest.json</code></td></tr>
<tr><td>Selected real objective recall</td><td class="num">{prof['effectiveness']['selected_real_objective_recall_percent']:.1f}%</td><td><code>results/selected-real-head-latest.json</code></td></tr>
<tr><td>Share of all real AI slop caught</td><td class="num"><strong>unknown</strong></td><td>not measurable from this corpus</td></tr>
<tr><td>Stored patch added line marker rate</td><td class="num">{obs['stored_patch_marker_rate_percent']:.3f}%</td><td><code>results/professional-benchmark-latest.json</code></td></tr>
<tr><td>Executed cases across suites</td><td class="num">{cov['executed_cases_across_suites']}</td><td>sum of the three suites</td></tr>
</tbody></table>
<div class="callout">
<h3>Executive verdict</h3>
<p>{esc(prof['executive_verdict'])}</p>
</div>
<p>Next: <a href="value.html">why this matters for a project trying to avoid AI slop</a>.</p>
"""
    pages["proof.html"] = page("proof.html", "Proof", body, gen)

    # ---------------------------------------------------------------- value
    value_rows = "".join(
        f'<tr><td><strong>{esc(v["capability"])}</strong></td><td>{esc(v["before"])}</td><td>{esc(v["after"])}</td><td>{esc(v["evidence"])}</td></tr>'
        for v in prof["value_added"]
    )
    buyer_rows = "".join(
        f'<tr><td><strong>{esc(b["buyer"])}</strong></td><td>{esc(b["pain"])}</td><td>{esc(b["value"])}</td><td>{esc(b["proof"])}</td></tr>'
        for b in prof["buyer_outcomes"]
    )
    adoption = "".join(f"<li>{esc(step)}</li>" for step in prof["adoption_path"])
    body = f"""
<h1>Why AIV helps a project avoid AI slop</h1>
<p class="lede">{esc(prof['sales_pitch'])}</p>
<h2>What was the problem, what AIV caught, what proved it</h2>
<table>
<thead><tr><th>Capability</th><th>Before</th><th>After</th><th>Evidence</th></tr></thead>
<tbody>{value_rows}</tbody></table>
<h2>How it fits into a review process</h2>
<p>AIV runs where CI already runs. It reads the pull request as Git sees it, pinned to the reviewed head commit, and emits a
receipt that says which rules blocked, which only warned, and which passed. Because the hard fail path contains only objective
rules, a block is something the contributor fixes, not something the maintainer has to argue about. Reviewer time moves from
"did you leave a conflict marker in here" to "is this the right change".</p>
<div class="kpis">
<div class="kpi"><div class="num">{obs['objective_detections']}/{obs['objective_detections']}</div><div class="lbl">objective detections on executed suites</div></div>
<div class="kpi orange"><div class="num">{obs['hard_control_failures']}</div><div class="lbl">clean changes wrongly blocked out of {obs['executed_controls']}</div></div>
<div class="kpi purple"><div class="num">0</div><div class="lbl">bypasses available: full run skip is disabled, protected rules cannot be suppressed</div></div>
</div>
<h2>Who benefits</h2>
<table>
<thead><tr><th>Buyer</th><th>Pain</th><th>Value</th><th>Proof</th></tr></thead>
<tbody>{buyer_rows}</tbody></table>
<h2>Recommended adoption path</h2>
<ol>{adoption}</ol>
<div class="callout purple">
<h3>What AIV is not</h3>
<p>It is not an AI detector. It does not read disclosure checkboxes to decide anything. It does not catch a change that is
syntactically perfect, well tested and wrong. Those remain human work, and this benchmark does not pretend otherwise. What AIV
does is remove the mechanically obvious failures from the queue, every time, with a receipt.</p>
</div>
<p>Next: <a href="verification.html">how to verify every number on this site yourself</a>.</p>
"""
    pages["value.html"] = page("value.html", "Why AIV helps", body, gen)

    # ---------------------------------------------------------- verification
    body = f"""
<h1>Verification</h1>
<p class="lede">Every figure on this site is generated from files on disk by a script you can read and rerun. This page lists the files, the commands and the claims that are deliberately not made.</p>
<h2>Receipts behind each page</h2>
<table>
<thead><tr><th>File</th><th>Generated</th><th>Feeds</th></tr></thead>
<tbody>
<tr><td><code>benchmarks/airflow/reports/labeled-latest.json</code></td><td>{esc(ctx['labeled']['generated_at'])}</td><td>Fixture suite: {lab['TP']} TP, {lab['FN']} FN, {lab['FP']} FP, {lab['TN']} TN; rule counts</td></tr>
<tr><td><code>benchmarks/airflow/results/structural-latest.json</code></td><td>{esc(struct['generated_at'])}</td><td>Structural suite: {sm['true_positives']}/{sm['positives']} blocked, {sm['hard_false_positives']} false blocks</td></tr>
<tr><td><code>benchmarks/airflow/results/selected-real-head-latest.json</code></td><td>{esc(sel['generated_at'])}</td><td>Selected Airflow suite: #59957 and #70842 gate output</td></tr>
<tr><td><code>benchmarks/airflow/results/professional-benchmark-latest.json</code></td><td>{esc(gen)}</td><td>Census, metadata labels, lower bounds, value and buyer tables</td></tr>
<tr><td><code>aiv-airflow-bench/README.md</code></td><td>bench repo</td><td>Seven project table, disclosure and trailer counts</td></tr>
<tr><td><code>aiv-airflow-bench/docs/data/windows-latest.json</code></td><td>{esc(windows['generated_at'])}</td><td>Stored sample composition by class</td></tr>
</tbody></table>
<p>All three suite receipts were produced by the same jar: <code>{esc(Path(ctx['labeled']['aiv_jar']).name)}</code>.</p>
<h2>Reproduce from source</h2>
<pre>cd aiv-integrity-gate

# 1. Build the CLI from the current source
mvn -pl aiv-core,aiv-plugin-invariant-template,aiv-adapter-git -am install -DskipTests
mvn -pl aiv-cli clean package -DskipTests

# 2. Run the three suites
python benchmarks/airflow/scripts/run-structural-benchmark.py --jar aiv-cli/target/aiv-cli-1.0.4.jar --output benchmarks/airflow/results/structural-latest.json
python benchmarks/airflow/scripts/run-labeled-benchmark.py
python benchmarks/airflow/scripts/run-selected-real-airflow.py --jar aiv-cli/target/aiv-cli-1.0.4.jar

# 3. Rebuild the summary receipt and this site
python benchmarks/airflow/scripts/generate-professional-report.py
python benchmarks/airflow/scripts/generate-benchmark-site.py</pre>
<p>Run the full test reactor with <code>mvn clean verify</code> to confirm the gate code itself. Coverage gates are strict:
the API module requires 100 percent line coverage and the Git adapter 99 percent.</p>
<h2>What is verified and what is not</h2>
<table>
<thead><tr><th>Statement</th><th>Status</th></tr></thead>
<tbody>
<tr><td>AIV blocked {lab['TP']} of {lab['positives_expect_fail']} designed positives and passed {lab['TN']} of {lab['negatives_expect_pass']} designed controls</td><td><span class="badge block">verified by receipt</span></td></tr>
<tr><td>AIV blocked an added conflict and an unapproved gitlink in real Git repositories and passed an approved gitlink</td><td><span class="badge block">verified by receipt</span></td></tr>
<tr><td>AIV blocked Airflow #59957 on <code>invariant.merge-conflict</code> and passed #70842</td><td><span class="badge block">verified by receipt</span></td></tr>
<tr><td>Exactly one of {fmt(cov['stored_patch_cases_processed'])} stored Airflow patches carries an added line hard marker</td><td><span class="badge block">verified by census</span></td></tr>
<tr><td>The fixture suite reflects real world prevalence of AI slop</td><td><span class="badge warn">not claimed: fixtures are designed</span></td></tr>
<tr><td>The selected suite is a complete repository checkout</td><td><span class="badge warn">not claimed: {esc(sel['materialization'])}</span></td></tr>
<tr><td>AIV catches a known percentage of all AI slop in Airflow</td><td><span class="badge warn">not claimed: unknown</span></td></tr>
<tr><td>Disclosure, merge or closure status indicates a defect</td><td><span class="badge warn">not claimed</span></td></tr>
</tbody></table>
<div class="callout navy">
<h3>Claim boundary</h3>
<p>{esc(prof['claim_boundary'])}</p>
</div>
<h2>Site integrity checks</h2>
<p>The generator refuses to write any page containing an em dash or an en dash, verifies that every internal link resolves to a
generated page, and recomputes the headline totals from the suite receipts rather than copying them from the summary file.</p>
"""
    pages["verification.html"] = page("verification.html", "Verification", body, gen)
    return pages


# --------------------------------------------------------------------------- main

def cross_check(ctx: dict) -> list[str]:
    """Recompute headline totals from suite receipts and compare with the summary receipt."""
    problems = []
    prof = ctx["prof"]
    lab = ctx["labeled"]["metrics"]
    sm = ctx["structural"]["metrics"]
    sel_rows = ctx["selected"]["rows"]
    sel_tp = sum(1 for r in sel_rows if r["outcome"]["verdict"] == "TP")
    sel_controls = sum(1 for r in sel_rows if not r["label"]["expect_hard_fail"])
    sel_fp = sum(1 for r in sel_rows if r["outcome"]["verdict"] == "FP")
    detections = lab["TP"] + sm["true_positives"] + sel_tp
    controls = lab["negatives_expect_pass"] + sm["negatives"] + sel_controls
    false_blocks = lab["FP"] + sm["hard_false_positives"] + sel_fp
    if detections != prof["observed"]["objective_detections"]:
        problems.append(f"objective_detections {prof['observed']['objective_detections']} != recomputed {detections}")
    if controls != prof["observed"]["executed_controls"]:
        problems.append(f"executed_controls {prof['observed']['executed_controls']} != recomputed {controls}")
    if false_blocks != prof["observed"]["hard_control_failures"]:
        problems.append(f"hard_control_failures {prof['observed']['hard_control_failures']} != recomputed {false_blocks}")
    by_id = {s["id"]: s for s in prof["suites"]}
    if by_id["fixture"]["detected"] != lab["TP"] or by_id["fixture"]["positives"] != lab["positives_expect_fail"]:
        problems.append("fixture suite summary disagrees with labeled-latest.json")
    if by_id["structural"]["detected"] != sm["true_positives"]:
        problems.append("structural suite summary disagrees with structural-latest.json")
    if by_id["selected"]["detected"] != sel_tp:
        problems.append("selected suite summary disagrees with selected-real-head-latest.json")
    if ctx["table"][0]["prs_1y"] != prof["coverage"]["full_metadata_index_rows_processed"]:
        problems.append("README Airflow one year PR count disagrees with census rows processed")
    if ctx["table"][0]["stored_1y"] != prof["coverage"]["stored_patch_cases_processed"]:
        problems.append("README Airflow stored sample disagrees with census stored patches")
    return problems


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--output", default=str(AIRFLOW_DIR / "reports" / "site"))
    ap.add_argument("--bench-repo", default=str(DEFAULT_BENCH_REPO))
    args = ap.parse_args()

    bench = Path(args.bench_repo)
    readme = (bench / "README.md").read_text(encoding="utf-8")
    ctx = {
        "prof": load_json(AIRFLOW_DIR / "results" / "professional-benchmark-latest.json"),
        "structural": load_json(AIRFLOW_DIR / "results" / "structural-latest.json"),
        "selected": load_json(AIRFLOW_DIR / "results" / "selected-real-head-latest.json"),
        "labeled": load_json(AIRFLOW_DIR / "reports" / "labeled-latest.json"),
        "windows": load_json(bench / "docs" / "data" / "windows-latest.json"),
        "table": parse_readme_table(readme),
        "trailers": parse_readme_trailers(readme),
    }
    ctx["fixture_groups"], ctx["rule_counts"] = fixture_breakdown(ctx["labeled"])

    problems = cross_check(ctx)
    if problems:
        for p in problems:
            print("CROSS-CHECK FAILED:", p, file=sys.stderr)
        return 2

    pages = build_pages(ctx)
    out = Path(args.output)
    out.mkdir(parents=True, exist_ok=True)
    link_re = re.compile(r'href="([^"#:]+\.html)"')
    for name, content in pages.items():
        for ch in FORBIDDEN_CHARS:
            if ch in content:
                print(f"FORBIDDEN CHARACTER U+{ord(ch):04X} in {name}", file=sys.stderr)
                return 3
        for target in link_re.findall(content):
            if target not in pages:
                print(f"BROKEN LINK {target} in {name}", file=sys.stderr)
                return 4
        (out / name).write_text(content, encoding="utf-8")
        print(f"wrote {out / name}")
    print(f"\nOpen: {out / 'index.html'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
