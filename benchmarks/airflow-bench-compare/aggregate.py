#!/usr/bin/env python3
"""Re-aggregates results/compare.json into summary.json + COMPARE.md with the corpus caveats applied.

Caveat handled here: the bench's abandoned/* and snapshot/* branches materialize real Airflow PRs as
HUNK FRAGMENTS, not whole files. The syntax gate therefore fails on most of them for BOTH builds; that
is a corpus artifact, not a detection. We report hard-fails with and without the syntax gate.
"""
import json
import sys
from collections import Counter
from pathlib import Path

import subprocess

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "benchmarks/airflow-bench-compare/results").resolve()
BENCH = Path(sys.argv[2] if len(sys.argv) > 2 else "../aiv-airflow-bench").resolve()
rows = json.loads((OUT / "compare.json").read_text(encoding="utf-8"))


def gates(s):
    return [g for g in s.split(";") if g]


def git_show(branch, path):
    p = subprocess.run(["git", "show", f"origin/{branch}:{path}"], cwd=BENCH, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    return p.stdout if p.returncode == 0 else None


def function_closes_before_eof(text, line_no):
    """True when the test function starting at line_no (1-based) is terminated inside the file.
    Fragment files (hunk-only recreations) frequently cut a test body off at EOF; such findings are
    corpus artifacts, not detections."""
    lines = text.split("\n")
    if line_no < 1 or line_no > len(lines):
        return True
    start = lines[line_no - 1]
    if start.lstrip().startswith(("def ", "async def ")):
        indent = len(start) - len(start.lstrip())
        depth = start.count("(") - start.count(")")
        j = line_no
        while depth > 0 and j < len(lines):
            depth += lines[j].count("(") - lines[j].count(")")
            j += 1
        for k in range(j, len(lines)):
            l = lines[k]
            if l.strip() and (len(l) - len(l.lstrip())) <= indent:
                return True
        return False
    depth = 0
    opened = False
    for l in lines[line_no - 1:]:
        for ch in l:
            if ch == "{":
                depth += 1
                opened = True
            elif ch == "}":
                depth -= 1
            if opened and depth == 0:
                return True
    return False


def fragment_artifact_findings(row):
    """Set of (file,line) assertion-free findings on real-PR fragments whose function is cut off at EOF."""
    if not (row["cohort"].startswith("abandoned/") or row["cohort"] == "snapshot"):
        return set()
    rep = OUT / "reports" / (row["branch"].replace("/", "__") + ".new.json")
    if not rep.exists():
        return set()
    d = json.loads(rep.read_text(encoding="utf-8"))
    out = set()
    for g in d.get("gates", []):
        if g["id"] != "test-adequacy":
            continue
        for f in g.get("findings", []):
            if f["rule_id"] != "test-adequacy.assertion-free-test":
                continue
            text = git_show(row["branch"], f["file"])
            if text is not None and not function_closes_before_eof(text, f["start_line"]):
                out.add((f["file"], f["start_line"]))
    return out


# annotate rows with real (non-artifact) hard findings for the new build
for r in rows:
    r["new_fragment_artifacts"] = sorted(f"{a}:{b}" for a, b in fragment_artifact_findings(r))
    rep = OUT / "reports" / (r["branch"].replace("/", "__") + ".new.json")
    real_hard = set()
    if rep.exists():
        d = json.loads(rep.read_text(encoding="utf-8"))
        arts = set(r["new_fragment_artifacts"])
        for g in d.get("gates", []):
            if g["passed"] or not g["blocks_ci"] or g["id"] == "syntax":
                continue
            if g["id"] != "test-adequacy":
                real_hard.add(g["id"])
                continue
            for f in g.get("findings", []):
                if f["rule_id"] == "test-adequacy.assertion-free-test" and f"{f['file']}:{f['start_line']}" not in arts:
                    real_hard.add("test-adequacy")
    r["new_hard_real"] = ";".join(sorted(real_hard))


def hard_wo_syntax(r, key):
    if key == "new":
        return gates(r["new_hard_real"])
    return [g for g in gates(r[f"{key}_hard"]) if g != "syntax"]


def agg_gt(key):
    gt = [r for r in rows if r["expected"] != "n/a"]
    tp = sum(1 for r in gt if r["expected"] == "FAIL" and r[f"{key}_verdict"] == "FAIL")
    fn = sum(1 for r in gt if r["expected"] == "FAIL" and r[f"{key}_verdict"] == "PASS")
    fp = sum(1 for r in gt if r["expected"] == "PASS" and r[f"{key}_verdict"] == "FAIL")
    tn = sum(1 for r in gt if r["expected"] == "PASS" and r[f"{key}_verdict"] == "PASS")
    return dict(n=len(gt), TP=tp, FN=fn, FP=fp, TN=tn,
                recall=(tp / (tp + fn)) if (tp + fn) else None, precision=(tp / (tp + fp)) if (tp + fp) else None)


def cohort(prefix):
    sub = [r for r in rows if r["cohort"].startswith(prefix)]
    d = dict(n=len(sub))
    for key in ("old", "new"):
        d[f"{key}_hard_fail"] = sum(1 for r in sub if r[f"{key}_verdict"] == "FAIL")
        d[f"{key}_hard_fail_excl_syntax"] = sum(1 for r in sub if hard_wo_syntax(r, key))
        d[f"{key}_any_signal"] = sum(1 for r in sub if r[f"{key}_hard"] or r[f"{key}_soft"])
        d[f"{key}_any_signal_excl_syntax"] = sum(1 for r in sub if hard_wo_syntax(r, key) or gates(r[f"{key}_soft"]))
        d[f"{key}_gate_counts"] = dict(Counter(g for r in sub for g in gates(r[f"{key}_hard"]) + gates(r[f"{key}_soft"])))
    d["new_rule_counts"] = dict(Counter(x for r in sub for x in gates(r["new_rules"])))
    return d


summary = {
    "branches": len(rows),
    "labeled_ground_truth": {"old": agg_gt("old"), "new": agg_gt("new")},
    "abandoned_real_prs": {k: cohort(p) for k, p in (("ai_disclosed", "abandoned/ai_disclosed"),
                                                     ("ai_signal_advisory", "abandoned/ai_signal_advisory"),
                                                     ("abandoned_clean", "abandoned/abandoned_clean"),
                                                     ("all", "abandoned/"))},
    "snapshots": cohort("snapshot"),
    "new_rules_fired": sorted({x for r in rows for x in gates(r["new_rules"])}),
    "old_rules_fired": sorted({x for r in rows for x in gates(r["old_rules"])}),
    "corpus_caveat": "abandoned/* and snapshot/* branches contain hunk fragments, not whole files; syntax verdicts on them are a corpus artifact shared by both builds.",
}
(OUT / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")

md = ["# Old (1.0.4, commit bd9a99d) vs new (v2 working tree) on aiv-airflow-bench", "",
      f"{len(rows)} branches, base ref `main` (three-dot merge-base diff), identical `.aiv/config.yaml` for both builds "
      "(the new build reads it from the base ref).", "",
      "## 1. Labeled ground truth (synthetic marker fixtures + case/*)", "",
      "| build | n | TP | FN | FP | TN | recall | precision |", "|---|---|---|---|---|---|---|---|"]
for k in ("old", "new"):
    g = summary["labeled_ground_truth"][k]
    md.append(f"| {k} | {g['n']} | {g['TP']} | {g['FN']} | {g['FP']} | {g['TN']} | {g['recall']:.0%} | {g['precision']:.0%} |")
md += ["", "Both builds score the same here. These fixtures were generated to match the invariant/design regexes; "
       "they prove the regexes work, not that slop is detected.", "",
       "## 2. Real abandoned Airflow PRs (recreated as hunk fragments; no hard ground truth)", "",
       "| cohort | n | old hard | new hard | old hard excl. syntax | new hard excl. syntax | old any signal excl. syntax | new any signal excl. syntax |",
       "|---|---|---|---|---|---|---|---|"]
for k, v in summary["abandoned_real_prs"].items():
    md.append(f"| {k} | {v['n']} | {v['old_hard_fail']} | {v['new_hard_fail']} | {v['old_hard_fail_excl_syntax']} | "
              f"{v['new_hard_fail_excl_syntax']} | {v['old_any_signal_excl_syntax']} | {v['new_any_signal_excl_syntax']} |")
v = summary["snapshots"]
md.append(f"| snapshot | {v['n']} | {v['old_hard_fail']} | {v['new_hard_fail']} | {v['old_hard_fail_excl_syntax']} | "
          f"{v['new_hard_fail_excl_syntax']} | {v['old_any_signal_excl_syntax']} | {v['new_any_signal_excl_syntax']} |")
arts = [(r["branch"], a) for r in rows for a in r["new_fragment_artifacts"]]
summary["fragment_artifacts_excluded"] = [f"{b} {a}" for b, a in arts]
(OUT / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
md += ["", "Corpus caveat: " + summary["corpus_caveat"],
       f"Assertion-free findings whose test body is cut off at the fragment's EOF are excluded from the new build's "
       f"'excl. syntax' columns as corpus artifacts ({len(arts)} findings): " + "; ".join(f"{b} {a}" for b, a in arts), "",
       "### Gates that fired on real PRs (new build, excl. syntax)", ""]
for k, v in summary["abandoned_real_prs"].items():
    if k == "all":
        md.append(f"- all abandoned: gates {v['new_gate_counts']} ; rules {v['new_rule_counts']}")
md += ["", "## 3. Per branch", "",
       "| branch | expected | old | new | old hard | new hard | new soft | new rules | notices |",
       "|---|---|---|---|---|---|---|---|---|"]
for r in rows:
    md.append(f"| {r['branch']} | {r['expected']} | {r['old_verdict']} | {r['new_verdict']} | {r['old_hard'] or '-'} | "
              f"{r['new_hard'] or '-'} | {r['new_soft'] or '-'} | {r['new_rules'] or '-'} | {r['new_notices'][:80] or '-'} |")
(OUT / "COMPARE.md").write_text("\n".join(md) + "\n", encoding="utf-8")
print(json.dumps({k: summary[k] for k in ("labeled_ground_truth", "abandoned_real_prs", "snapshots", "new_rules_fired")}, indent=1))
