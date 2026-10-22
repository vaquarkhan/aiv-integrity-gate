#!/usr/bin/env python3
"""Generates the README / deck figures (matplotlib only, deterministic, no network).

Usage:  python docs/images/generate_figures.py [--summary benchmarks/airflow-bench-compare/results/summary.json]
Writes PNGs into docs/images/.
"""
import argparse
import json
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch

OUT = Path(__file__).resolve().parent
NAVY = "#0F2B46"
TEAL = "#0F766E"
RED = "#B42318"
AMBER = "#B54708"
GREY = "#667085"
LIGHT = "#F2F4F7"
GREEN = "#027A48"


def box(ax, x, y, w, h, text, fc=LIGHT, ec=NAVY, fs=10, tc=NAVY, bold=False, radius=0.02):
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle=f"round,pad=0.01,rounding_size={radius}",
                                fc=fc, ec=ec, lw=1.4))
    ax.text(x + w / 2, y + h / 2, text, ha="center", va="center", fontsize=fs, color=tc,
            fontweight="bold" if bold else "normal", wrap=True)


def arrow(ax, x1, y1, x2, y2, color=NAVY):
    ax.add_patch(FancyArrowPatch((x1, y1), (x2, y2), arrowstyle="-|>", mutation_scale=14, lw=1.4, color=color))


def fig_problem():
    fig, ax = plt.subplots(figsize=(11, 5.2), dpi=160)
    labels = [
        "Agent PRs with a\nsecurity smell\n(KDD '26, 4,022 PRs)",
        "Leaked credentials\nmissed by review\n(KDD '26)",
        "SWE-bench Verified\ninstances with surviving\nmutants (STING)",
        "AI-introduced static\nissues still alive at HEAD\n(302k commits)",
        "Hallucinated package\nnames, frontier models\n(2026 replication)",
        "Real AI-signal Airflow\nPRs caught by AIV 1.0.4\n(aiv-airflow-bench)",
    ]
    vals = [38.9, 81.1, 77.0, 22.7, 6.1, 5.3]
    colors = [AMBER, RED, RED, AMBER, AMBER, RED]
    bars = ax.barh(labels, vals, color=colors)
    for b, v in zip(bars, vals):
        ax.text(b.get_width() + 1, b.get_y() + b.get_height() / 2, f"{v:g}%", va="center", fontsize=11, color=NAVY,
                fontweight="bold")
    ax.set_xlim(0, 100)
    ax.invert_yaxis()
    ax.set_xlabel("percent")
    ax.set_title("AI slop is measurable, and marker-matching does not catch it", fontsize=14, color=NAVY, loc="left")
    ax.spines[["top", "right"]].set_visible(False)
    ax.tick_params(axis="y", labelsize=9)
    fig.tight_layout()
    fig.savefig(OUT / "fig-problem-evidence.png")
    plt.close(fig)


def fig_pipeline():
    fig, ax = plt.subplots(figsize=(12, 5.4), dpi=160)
    ax.set_xlim(0, 12)
    ax.set_ylim(0, 5.4)
    ax.axis("off")
    ax.set_title("AIV v2 run: evidence on the diff, config pinned to the protected branch", fontsize=14, color=NAVY,
                 loc="left")
    box(ax, 0.2, 3.6, 2.2, 1.2, "git diff\nbase...head\n(+ added-line index)", fs=10)
    box(ax, 0.2, 1.6, 2.2, 1.2, ".aiv/config.yaml\nread from BASE ref\n(git show base:…)", fc="#E6F4F1", ec=TEAL, fs=10)
    box(ax, 0.2, 0.1, 2.2, 1.1, "/aiv skip?\ndefault-deny unless\nallowlist + signed", fc="#FDECEC", ec=RED, fs=9)
    arrow(ax, 2.4, 4.2, 3.1, 3.4)
    arrow(ax, 2.4, 2.2, 3.1, 2.9)
    arrow(ax, 2.4, 0.65, 3.1, 2.6)
    box(ax, 3.1, 1.9, 1.9, 1.9, "Orchestrator\nServiceLoader\ngates", fc=LIGHT, fs=10, bold=True)
    arrow(ax, 5.0, 3.3, 5.7, 3.9)
    arrow(ax, 5.0, 2.4, 5.7, 1.6)
    hard = ["syntax", "invariant (markers)", "test-adequacy: assertion-free", "package-existence", "dependency",
            "security", "design"]
    soft = ["density", "cohesion", "duplication", "test-adequacy: missing-tests", "pr-metadata*", "license-header*",
            "rules* (packs: airflow)", "external* (per tool)", "jev* (model, never blocks)"]
    box(ax, 5.7, 3.1, 3.0, 2.1, "HARD (blocks CI)\n" + "\n".join("• " + h for h in hard), fc="#FDECEC", ec=RED, fs=8.5)
    box(ax, 5.7, 0.1, 3.0, 2.85, "ADVISORY (warn + label)\n" + "\n".join("• " + s for s in soft) + "\n* opt-in (preset)",
        fc="#FFF4E5", ec=AMBER, fs=8)
    arrow(ax, 8.7, 4.1, 9.4, 3.2)
    arrow(ax, 8.7, 1.4, 9.4, 2.4)
    box(ax, 9.4, 1.5, 2.4, 2.4, "Report\nstdout · JSON · SARIF\nGitHub Checks · PR label\n\nnotices: CONFIG-CHANGE\nSKIP-IGNORED, UNVERIFIED",
        fc="#E6F4F1", ec=TEAL, fs=9)
    ax.text(9.4, 0.6, "exit 0 pass · 1 hard fail\n2 config · 3 git", fontsize=9, color=GREY)
    fig.tight_layout()
    fig.savefig(OUT / "fig-pipeline-v2.png")
    plt.close(fig)


def fig_trust_boundary():
    fig, axes = plt.subplots(1, 2, figsize=(12, 4.8), dpi=160)
    for ax, title, rows, color in (
            (axes[0], "AIV 1.0.4: one line defeats the gate",
             ["commit message line  'aiv skip'  ->  all gates skipped (empty allowlist = open)",
              "PR edits .aiv/config.yaml  ->  gate judges itself with PR's config",
              "// aiv-disable  (no rule)  ->  every finding in the file hidden",
              "trusted_authors + ANY signature status (expired, revoked, untrusted)",
              "missing toolchain / manifest / registry  ->  silent PASS"], RED),
            (axes[1], "AIV v2: default-deny trust boundary",
             ["'aiv skip' ignored unless allowlist AND good signature (%G? == G); notice [SKIP-IGNORED]",
              "config read from base ref; PR config edits flagged [CONFIG-CHANGE]",
              "file-level aiv-disable must name a rule; bare / '*' ignored",
              "trusted_authors requires %G? == G only",
              "cannot-check is reported (UNVERIFIED), never silently passed"], GREEN)):
        ax.axis("off")
        ax.set_title(title, fontsize=12.5, color=color, loc="left")
        for i, r in enumerate(rows):
            ax.add_patch(FancyBboxPatch((0.01, 0.86 - i * 0.18), 0.98, 0.14, boxstyle="round,pad=0.01",
                                        fc=("#FDECEC" if color == RED else "#E8F5EE"), ec=color, lw=1.2,
                                        transform=ax.transAxes))
            ax.text(0.03, 0.93 - i * 0.18, r, fontsize=8.6, color=NAVY, va="center", transform=ax.transAxes)
    fig.tight_layout()
    fig.savefig(OUT / "fig-trust-boundary.png")
    plt.close(fig)


def fig_slop_classes():
    classes = [
        ("Paste artifacts, conflict markers, chatter", "invariant", 1, 1),
        ("Tests that cannot fail (assertion-free)", "test-adequacy", 0, 1),
        ("Hallucinated / slopsquattable packages", "package-existence", 0, 1),
        ("Imports not in manifest", "dependency", 1, 1),
        ("Won't parse", "syntax", 1, 1),
        ("Hard-coded secrets", "security (default on)", 0, 1),
        ("Copy-paste duplication", "duplication (advisory)", 0, 1),
        ("Production change with no test", "test-adequacy (advisory)", 0, 1),
        ("Unrelated changes bundled", "cohesion (advisory)", 1, 1),
        ("PR hygiene, Gen-AI disclosure", "pr-metadata (wired to PR env)", 0, 1),
        ("Framework defects that import fine, fail at runtime", "rules + packs (airflow shipped), your YAML", 0, 1),
        ("Findings from tools you already run (ruff, semgrep...)", "external (SARIF, same receipt)", 0, 1),
        ("Compiles, tests pass, semantically wrong", "not decidable: routed to review; opt-in jev gives a probability, never a block", 0, 0),
    ]
    fig, ax = plt.subplots(figsize=(12, 6.6), dpi=160)
    ax.axis("off")
    ax.set_title("Slop classes: what is decidable, and which build covers it", fontsize=14, color=NAVY, loc="left")
    ax.text(0.02, 0.95, "slop class", transform=ax.transAxes, fontsize=10, color=GREY, fontweight="bold")
    ax.text(0.50, 0.95, "sensor", transform=ax.transAxes, fontsize=10, color=GREY, fontweight="bold")
    ax.text(0.83, 0.95, "1.0.4", transform=ax.transAxes, fontsize=10, color=GREY, fontweight="bold")
    ax.text(0.92, 0.95, "v2", transform=ax.transAxes, fontsize=10, color=GREY, fontweight="bold")
    for i, (c, s, old, new) in enumerate(classes):
        y = 0.89 - i * 0.068
        ax.text(0.02, y, c, transform=ax.transAxes, fontsize=9.5, color=NAVY, va="center")
        ax.text(0.50, y, s, transform=ax.transAxes, fontsize=9.5, color=GREY, va="center")
        for x, v in ((0.84, old), (0.93, new)):
            ax.text(x, y, "✔" if v else "–", transform=ax.transAxes, fontsize=13, va="center", ha="center",
                    color=(GREEN if v else RED), fontweight="bold")
    ax.text(0.02, 0.0, "✔ = a deterministic sensor exists for the class. The last class is made visible (evidence pack) and routed, never silently passed.",
            transform=ax.transAxes, fontsize=8.5, color=GREY)
    fig.tight_layout()
    fig.savefig(OUT / "fig-slop-classes.png")
    plt.close(fig)


def fig_results(summary_path: Path):
    if not summary_path or not summary_path.exists():
        return
    s = json.loads(summary_path.read_text(encoding="utf-8"))
    gt = s["labeled_ground_truth"]
    ab = s["abandoned_real_prs"]
    fig, axes = plt.subplots(1, 2, figsize=(12, 4.8), dpi=160)
    ax = axes[0]
    keys = ["TP", "FN", "FP", "TN"]
    old = [gt["old"][k] for k in keys]
    new = [gt["new"][k] for k in keys]
    x = range(len(keys))
    ax.bar([i - 0.2 for i in x], old, width=0.4, color=GREY, label="AIV 1.0.4")
    ax.bar([i + 0.2 for i in x], new, width=0.4, color=TEAL, label="AIV v2")
    for i, (o, n) in enumerate(zip(old, new)):
        ax.text(i - 0.2, o + 0.3, str(o), ha="center", fontsize=9)
        ax.text(i + 0.2, n + 0.3, str(n), ha="center", fontsize=9)
    ax.set_xticks(list(x))
    ax.set_xticklabels(keys)
    ax.set_title(f"Labeled fixtures (n={gt['new']['n']}): recall old {gt['old']['recall']:.0%} / new {gt['new']['recall']:.0%}",
                 fontsize=11, color=NAVY, loc="left")
    ax.legend(frameon=False)
    ax.spines[["top", "right"]].set_visible(False)

    ax = axes[1]
    cohorts = ["ai_disclosed", "ai_signal_advisory", "abandoned_clean"]
    def pick(c, k):
        return ab[c].get(k + "_excl_syntax", ab[c][k])
    oh = [pick(c, "old_hard_fail") for c in cohorts]
    nh = [pick(c, "new_hard_fail") for c in cohorts]
    oa = [pick(c, "old_any_signal") for c in cohorts]
    na = [pick(c, "new_any_signal") for c in cohorts]
    ns = [ab[c]["n"] for c in cohorts]
    x = range(len(cohorts))
    ax.bar([i - 0.3 for i in x], oh, width=0.2, color=GREY, label="old hard-fail")
    ax.bar([i - 0.1 for i in x], nh, width=0.2, color=RED, label="new hard-fail")
    ax.bar([i + 0.1 for i in x], oa, width=0.2, color="#98A2B3", label="old any signal")
    ax.bar([i + 0.3 for i in x], na, width=0.2, color=TEAL, label="new any signal")
    ax.set_xticks(list(x))
    ax.set_xticklabels([f"{c}\n(n={n})" for c, n in zip(cohorts, ns)], fontsize=9)
    ax.set_title("Real abandoned Airflow PRs: what fires\n(syntax excluded: corpus is hunk fragments)",
                 fontsize=10, color=NAVY, loc="left")
    ax.yaxis.get_major_locator().set_params(integer=True)
    ax.legend(frameon=False, fontsize=8)
    ax.spines[["top", "right"]].set_visible(False)
    fig.tight_layout()
    fig.savefig(OUT / "fig-old-vs-new-results.png")
    plt.close(fig)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--summary", default="")
    a = ap.parse_args()
    fig_problem()
    fig_pipeline()
    fig_trust_boundary()
    fig_slop_classes()
    fig_results(Path(a.summary) if a.summary else None)
    print("figures written to", OUT)


if __name__ == "__main__":
    main()
