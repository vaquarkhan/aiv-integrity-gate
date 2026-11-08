# Copyright 2026 Vaquar Khan
# SPDX-License-Identifier: Apache-2.0
"""Label Apache Airflow PRs in the stored corpus with the slop-like patterns maintainers name.

Uses only data already in aiv-airflow-bench and this repository (no network):

* Tier A, index (corpus/airflow-all/prs.jsonl, 15,575 PRs): title, body, size, outcome, AI disclosure.
* Tier B, patches (corpus/ai-year/prs + corpus/e2e-proof/prs, Airflow only): added and removed lines.
* Tier C, AIV census (benchmarks/airflow/reports/census-latest.json): real AIV gates on 627 real checkouts.

    python benchmarks/airflow/scripts/label-pr-slop-patterns.py [--bench ../aiv-airflow-bench]

Writes benchmarks/airflow/results/pr-pattern-labels-latest.jsonl (one row per PR with its labels and
evidence) and benchmarks/airflow/reports/pr-pattern-labels-latest.md (counts, merge rates, examples).
Labels describe the change, never who wrote it. Heuristic labels are candidates for review, not verdicts.
"""
import argparse
import hashlib
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ap = argparse.ArgumentParser()
ap.add_argument("--bench", default=str(ROOT.parent / "aiv-airflow-bench"))
ap.add_argument("--out-jsonl", default=str(ROOT / "benchmarks/airflow/results/pr-pattern-labels-latest.jsonl"))
ap.add_argument("--out-md", default=str(ROOT / "benchmarks/airflow/reports/pr-pattern-labels-latest.md"))
ARGS = ap.parse_args()
C = Path(ARGS.bench) / "corpus"

# ---------------------------------------------------------------- label catalogue
LABELS = [
    ("L1", "small-fix-many-files", "A", "Title promises a small change (typo, docs, fix, bump, minor) but 20+ files change"),
    ("L2", "big-pr", "A", "50+ files or 1,000+ changed lines"),
    ("L3", "small-fix-1000-lines", "A", "Small-change title with 1,000+ changed lines"),
    ("L4", "commit-churn", "-", "Every commit rewrites the whole change (needs per-commit data)"),
    ("L5", "description-mismatch", "B", "Description claims tests the diff does not add, or the title promises a fix but the diff is mostly new code"),
    ("L6", "security-smell", "B", "Added non-test code with a hardcoded secret, disabled TLS, shell=True, eval/exec, unsafe deserialization, SQL built from strings"),
    ("L7", "hallucination-or-ai-artifact", "B", "Placeholder or AI-chatter text in added code, unfilled template values, or AIV hard markers"),
    ("L8", "scaffolding-non-modular", "B", "Stub bodies, one very long new function, a 500+ line new source file, or deep nesting"),
    ("L9", "duplicate-not-reuse", "B", "Same code block or helper added in several files, duplicated test bodies, or a new utils/helpers module"),
]
SMALL = re.compile(r"(?i)\b(typos?|spelling|grammar|docs?|documentation|readme|fix(es|ed)?|bug|bump|minor|small|tweak|nit)\b")
BOT = re.compile(r"(?i)(\[bot\]$|^github-actions|^dependabot|^renovate)")
MECHANICAL_TITLE = re.compile(r"(?i)^\s*(\[v\d+-\d+-test\]|\[v\d|sync v\d|prepare (providers )?release|bump the |update .*constraints|upgrade important ci)")

TEST_PATH = re.compile(r"(^|/)(tests?|testing|__tests__|testdata)/|(^|/)test_[^/]*\.py$|_test\.(py|go)$|\.(test|spec)\.[jt]sx?$|conftest\.py$")
DOC_PATH = re.compile(r"\.(md|rst|txt|adoc|svg|png|jpg)$|(^|/)docs?/|newsfragments/")
SRC_EXT = re.compile(r"\.(py|java|go|ts|tsx|js|jsx|scala|kt|rs|c|cc|cpp|h|sh)$")

SECURITY = [
    ("hardcoded-secret", re.compile(r"""(?i)\b(password|passwd|secret|api_?key|access_?key|auth_?token|private_?key)\b\s*[:=]\s*["'][^"'\s{}$<>]{8,}["']""")),
    ("aws-key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("private-key", re.compile(r"-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY")),
    ("tls-verify-off", re.compile(r"\bverify\s*=\s*False\b|_create_unverified_context|CERT_NONE")),
    ("shell-true", re.compile(r"\bshell\s*=\s*True\b")),
    ("eval-exec", re.compile(r"(?<![\w.])(eval|exec)\s*\(")),
    ("unsafe-deserialize", re.compile(r"\bpickle\.loads?\(|\byaml\.load\((?![^)]*SafeLoader)|\bmarshal\.loads\(")),
    ("sql-from-string", re.compile(r"\.execute\(\s*f[\"']|\.execute\([^)]*\.format\(|\.execute\([^)]*[\"']\s*%\s*[\w(]")),
    ("os-system", re.compile(r"\bos\.system\(|\bos\.popen\(")),
    ("world-writable", re.compile(r"\b0o?777\b|chmod\s+777")),
    ("insecure-temp", re.compile(r"\btempfile\.mktemp\(")),
]
PLACEHOLDER_SECRET = re.compile(r"(?i)(test|dummy|fake|example|sample|changeme|xxx|placeholder|secret123|password123|mock)")
AI_ARTIFACT = [
    ("todo-implement", re.compile(r"(?i)\b(todo|fixme)\b\s*:?\s*(implement|add (the )?(actual|real)|replace with|fill in)")),
    ("placeholder-word", re.compile(r"(?i)#.*\bplaceholder\b|\bplaceholder (implementation|logic|value)\b")),
    ("your-x-here", re.compile(r"(?i)\byour[_\- ]?(api[_\- ]?key|token|password|secret|value|code|logic)[_\- ]?here\b")),
    ("elided-code", re.compile(r"(?i)(\.\.\.|#|//)\s*(rest of|existing|remaining) (the )?(code|implementation|logic)")),
    ("ai-chatter", re.compile(r"(?i)\b(as an ai( language model)?|i cannot (help|assist)|i'?m sorry, but|here is the (updated|modified|complete) code)\b")),
    ("implementation-here", re.compile(r"(?i)\b(implementation|logic) (goes|would go) here\b|\bin a real (implementation|scenario|application)\b")),
    ("demo-only", re.compile(r"(?i)\bfor (demonstration|illustration) purposes\b|\bsimplified (version|implementation)\b|\bmock (implementation|response) for now\b")),
    ("edit-artifact", re.compile(r"^(<<<<<<< SEARCH|>>>>>>> REPLACE)\s*$")),
]
DEF = re.compile(r"^(\s*)(async\s+)?def\s+([A-Za-z_]\w*)\s*\(")
CLASS = re.compile(r"^(\s*)class\s+\w+")
STUB = re.compile(r"^\s*(pass|\.\.\.|raise NotImplementedError(\(.*\))?|return None\s*#.*todo.*)\s*$", re.I)
TEST_DEF = re.compile(r"^\s*(async\s+)?def\s+test\w*\s*\(|^\s*@(Test|ParameterizedTest)\b|^\s*(it|test)\s*\(\s*['\"`]")
CLAIM_COUNT = re.compile(r"(?i)\b(?:added|adds|add|wrote|introduces?|includes?)\s+(\d{1,4})\s+(?:new\s+|unit\s+|additional\s+)*(?:test cases|tests)\b"
                         r"|(?<![#\w.])(\d{1,4})\s+(?:new|additional)\s+(?:unit\s+)?(?:test cases|tests)\b")
CLAIM_ADDED = re.compile(r"(?i)\b(added|adds|add|wrote|new|includes?|updated?)\b[^.\n]{0,30}?\b(unit |integration )?tests?\b")
NEGATION = re.compile(r"(?i)\b(no|not|without|none|nothing|never|will|would|could|can|should|if|happy to|todo|follow-?up)\b|n't")
COMMENT = re.compile(r"^\s*(#|//|/\*|\*|--)")
HELPER_MODULE = re.compile(r"(?i)(^|/)[^/]*(utils?|helpers?|common|misc|tools)[^/]*\.py$")


def small_intent(title):
    return bool(SMALL.search(title or ""))


def mechanical(user, title):
    return bool(BOT.search(user or "") or MECHANICAL_TITLE.search(title or ""))


def norm(line):
    return re.sub(r"\s+", " ", line).strip()


def ident_norm(line):
    s = re.sub(r"(['\"]).*?\1", "S", norm(line))
    s = re.sub(r"\b\d+(\.\d+)?\b", "N", s)
    return re.sub(r"\b[A-Za-z_]\w*\b", "I", s)


def windows(lines, k=6, f=norm):
    sig = [x for x in (f(l) for l in lines) if len(x) > 3 and not COMMENT.match(x)
           and not x.startswith(("import ", "from ", "package ", "@")) and x not in ("{", "}", "):", "])", ")")]
    return {hashlib.sha1("\n".join(sig[i:i + k]).encode()).hexdigest() for i in range(len(sig) - k + 1)}


def added(patch):
    out = []
    for l in (patch or "").split("\n"):
        if l.startswith("+") and not l.startswith("+++"):
            out.append(l[1:])
    return out


def body_claims(body, test_defs, test_files, src_lines):
    clean = re.sub(r"(?s)<!--.*?-->", "", body or "")
    claimed = 0
    for m in CLAIM_COUNT.finditer(clean):
        claimed = max(claimed, int(m.group(1) or m.group(2)))
    says_added = False
    for line in clean.split("\n"):
        if re.match(r"^\s*[-*]\s*\[\s\]", line):
            continue
        for m in CLAIM_ADDED.finditer(line):
            if not NEGATION.search(line[max(0, m.start() - 25):m.end()]):
                says_added = True
    reasons = []
    if claimed and claimed > test_defs + 2:
        reasons.append(f"claims {claimed} tests added, diff adds {test_defs} test functions")
    if says_added and test_files == 0 and src_lines > 0:
        reasons.append("says tests were added, no test file changed")
    return reasons


# ---------------------------------------------------------------- tier A: index
index = {}
for line in (C / "airflow-all" / "prs.jsonl").read_text(encoding="utf-8").split("\n"):
    if line.strip():
        r = json.loads(line)
        index[r["number"]] = r


def outcome(r):
    return "merged" if r.get("merged") else ("closed" if (r.get("state") or "").upper() == "CLOSED" else "open")


def ai_flag(r):
    return r.get("label") in ("ai_disclosed", "ai_tool_mention", "ai_slop_discussion") or bool(r.get("generated_by"))


rows = {}
for n, r in index.items():
    lines = (r.get("additions") or 0) + (r.get("deletions") or 0)
    files = r.get("changed_files") or 0
    t = r.get("title") or ""
    row = {"pr": n, "url": r.get("url"), "title": t, "user": r.get("user"), "outcome": outcome(r), "ai": ai_flag(r),
           "files": files, "lines": lines, "mechanical": mechanical(r.get("user"), t), "tiers": ["A"],
           "labels": {}}
    if small_intent(t) and files >= 20:
        row["labels"]["small-fix-many-files"] = f"{files} files"
    if files >= 50 or lines >= 1000:
        row["labels"]["big-pr"] = f"{files} files, {lines} lines"
    if small_intent(t) and lines >= 1000:
        row["labels"]["small-fix-1000-lines"] = f"{lines} lines"
    rows[n] = row

# ---------------------------------------------------------------- tier B: patches
patch_prs = {}
for d in [C / "ai-year" / "prs", C / "e2e-proof" / "prs"]:
    for f in sorted(d.glob("*.json")):
        try:
            pr = json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            continue
        if pr.get("files") and pr.get("number") not in patch_prs:
            patch_prs[pr["number"]] = pr

for n, pr in patch_prs.items():
    row = rows.get(n)
    if row is None:
        t = pr.get("title") or ""
        row = rows[n] = {"pr": n, "url": pr.get("url"), "title": t, "user": (pr.get("user") or {}).get("login") if isinstance(pr.get("user"), dict) else pr.get("user"),
                         "outcome": "merged" if pr.get("merged") else ("closed" if (pr.get("state") or "").lower() == "closed" else "open"),
                         "ai": bool((pr.get("flag") or {}).get("ai_related")), "files": pr.get("changed_files") or 0,
                         "lines": (pr.get("additions") or 0) + (pr.get("deletions") or 0), "tiers": [], "labels": {}}
        row["mechanical"] = mechanical(row["user"], t)
    row["tiers"].append("B")
    sec, art, scaf, dup = Counter(), Counter(), [], []
    sig = defaultdict(set)  # label -> sub-signal keys, for per-signal merge rates
    blocks, rblocks, tblocks = defaultdict(set), defaultdict(set), defaultdict(set)
    fn_files = defaultdict(set)
    test_defs = test_files = src_lines = new_src_lines = 0
    for fl in pr["files"]:
        path = fl.get("filename") or ""
        add = added(fl.get("patch"))
        is_test, is_doc = bool(TEST_PATH.search(path)), bool(DOC_PATH.search(path))
        is_src = bool(SRC_EXT.search(path)) and not is_test and not is_doc
        if is_test:
            test_files += 1
            test_defs += sum(1 for l in add if TEST_DEF.match(l))
            for w in windows(add):
                tblocks[w].add(path)
        if is_src:
            src_lines += (fl.get("additions") or 0) + (fl.get("deletions") or 0)
            if fl.get("status") == "added":
                new_src_lines += fl.get("additions") or 0
        if not is_doc:
            for l in add:
                for name, rx in AI_ARTIFACT:
                    if rx.search(l):
                        art[name] += 1
        if not is_src:
            continue
        for l in add:
            for name, rx in SECURITY:
                if rx.search(l) and not (name == "hardcoded-secret" and PLACEHOLDER_SECRET.search(l)):
                    sec[name] += 1
        # scaffolding: stubs, long functions, god files, nesting
        stubs, longest, current, depth = 0, 0, 0, 0
        for i, l in enumerate(add):
            if DEF.match(l) or CLASS.match(l):
                longest = max(longest, current)
                current = 0
                nxt = next((x for x in add[i + 1:] if x.strip() and not x.strip().startswith(("#", '"""', "'''"))), "")
                if DEF.match(l) and STUB.match(nxt):
                    stubs += 1
                if DEF.match(l):
                    fn_files[DEF.match(l).group(3)].add(path)
            else:
                current += 1
            if path.endswith(".py") and len(l) - len(l.lstrip(" ")) >= 24 and l.strip():
                depth += 1
        longest = max(longest, current)
        if stubs >= 2:
            scaf.append(f"{stubs} stub functions in {path}")
            sig["scaffolding-non-modular"].add("stub-functions")
        if longest >= 150 and any(DEF.match(x) for x in add):
            scaf.append(f"~{longest}-line function body in {path}")
            sig["scaffolding-non-modular"].add("long-function")
        if fl.get("status") == "added" and (fl.get("additions") or 0) >= 500:
            scaf.append(f"new {fl.get('additions')}-line file {path}")
            sig["scaffolding-non-modular"].add("new-500-line-file")
        if depth >= 10:
            scaf.append(f"{depth} lines nested 6+ levels in {path}")
            sig["scaffolding-non-modular"].add("deep-nesting")
        if fl.get("status") == "added" and HELPER_MODULE.search(path):
            dup.append(f"new helper module {path}")
            sig["duplicate-not-reuse"].add("new-helper-module")
        for w in windows(add):
            blocks[w].add(path)
        for w in windows(add, f=ident_norm):
            rblocks[w].add(path)
    exact = sum(1 for v in blocks.values() if len(v) > 1)
    renamed = sum(1 for v in rblocks.values() if len(v) > 1)
    test_dup = sum(1 for v in tblocks.values() if len(v) > 1)
    same_fn = sorted(n for n, v in fn_files.items() if len(v) > 1 and not n.startswith("_") and n not in ("__init__", "main", "execute", "run", "setup", "get", "set"))
    for cond, key, text in ((exact, "identical-blocks", f"{exact} identical 6-line blocks across files"),
                            (renamed >= 2, "renamed-copies", f"{renamed} renamed-copy blocks across files"),
                            (test_dup, "duplicated-tests", f"{test_dup} duplicated test blocks across files"),
                            (same_fn, "same-helper-in-several-files",
                             f"same helper defined in several files: {', '.join(same_fn[:3])}")):
        if cond:
            dup.append(text)
            sig["duplicate-not-reuse"].add(key)
    mism = body_claims(pr.get("body"), test_defs, test_files, src_lines)
    if mism:
        sig["description-mismatch"].add("test-claims")
    if small_intent(row["title"]) and new_src_lines >= 300:
        mism.append(f"small-change title, {new_src_lines} lines of new source files")
        sig["description-mismatch"].add("small-title-new-code")
    sig["security-smell"].update(sec)
    sig["hallucination-or-ai-artifact"].update(art)
    if pr.get("markers_in_patch"):
        sig["hallucination-or-ai-artifact"].add("discovery-marker")
    row["signals"] = {k: sorted(v) for k, v in sig.items() if v}
    if mism:
        row["labels"]["description-mismatch"] = "; ".join(mism)
    if sec:
        row["labels"]["security-smell"] = ", ".join(f"{k} x{v}" for k, v in sec.items())
    if art or pr.get("markers_in_patch"):
        row["labels"]["hallucination-or-ai-artifact"] = ", ".join([f"{k} x{v}" for k, v in art.items()]
                                                                    + [f"marker {m}" for m in (pr.get("markers_in_patch") or [])][:3])
    if scaf:
        row["labels"]["scaffolding-non-modular"] = "; ".join(scaf[:3])
    if dup:
        row["labels"]["duplicate-not-reuse"] = "; ".join(dup[:3])
    row["patch_truncated"] = bool(pr.get("files_truncated") or any(x.get("patch_truncated") or x.get("patch_omitted") for x in pr["files"]))

# ---------------------------------------------------------------- tier C: AIV census
census = json.loads((ROOT / "benchmarks/airflow/reports/census-latest.json").read_text(encoding="utf-8"))
census_rules = Counter()
census_n = 0
for case in census["cases"]:
    if case.get("source") != "github" or not case.get("pr"):
        continue
    census_n += 1
    hits = [f.get("rule_id") for g in case.get("gates") or [] for f in g.get("findings") or []]
    census_rules.update(hits)
    row = rows.get(case["pr"])
    if row is None:
        continue
    row["tiers"].append("C")
    hard = [h for h in hits if h and h.split(".")[0] in ("invariant", "package-existence", "syntax") or (h or "").startswith("design.forbidden")]
    if hard:
        prev = row["labels"].get("hallucination-or-ai-artifact")
        row["labels"]["hallucination-or-ai-artifact"] = "; ".join(x for x in [prev, "AIV: " + ", ".join(hard)] if x)
    sec_hits = [h for h in hits if (h or "").startswith("security.")]
    if sec_hits:
        row["labels"]["security-smell"] = "; ".join(x for x in [row["labels"].get("security-smell"), "AIV: " + ", ".join(sec_hits)] if x)
    dup_hits = [h for h in hits if (h or "").startswith("duplication.")]
    if dup_hits:
        row["labels"]["duplicate-not-reuse"] = "; ".join(x for x in [row["labels"].get("duplicate-not-reuse"), "AIV: " + ", ".join(dup_hits)] if x)

# ---------------------------------------------------------------- write + summarize
Path(ARGS.out_jsonl).parent.mkdir(parents=True, exist_ok=True)
with open(ARGS.out_jsonl, "w", encoding="utf-8") as fh:
    for row in sorted(rows.values(), key=lambda r: r["pr"]):
        fh.write(json.dumps(row, ensure_ascii=False) + "\n")


def pct(a, b):
    return f"{100 * a / b:.1f}%" if b else "n/a"


def stats(group):
    dec = [r for r in group if r["outcome"] in ("merged", "closed")]
    m = sum(r["outcome"] == "merged" for r in dec)
    return len(group), len(dec), m


tier_rows = {"A": [r for r in rows.values() if "A" in r["tiers"]], "B": [r for r in rows.values() if "B" in r["tiers"]]}
md = ["# Airflow PR pattern labels (stored corpus)", "",
      "Generated by `benchmarks/airflow/scripts/label-pr-slop-patterns.py` from data already in the benchmark "
      "repository. Labels describe the change, not the author. Heuristic labels (tier B) are candidates for review.", ""]
for tier, name in (("A", "index, 15,575 PRs: title, body, size, outcome"), ("B", "stored patches"), ):
    n, dec, m = stats(tier_rows[tier])
    human = [r for r in tier_rows[tier] if not r["mechanical"]]
    md.append(f"- Tier {tier} ({name}): {n} PRs, {dec} decided, baseline merge rate {pct(m, dec)}; "
              f"{len(human)} not bot/backport/release (`human`).")
md.append(f"- Tier C (real AIV on checkouts): {census_n} PRs; all findings: "
          f"{', '.join(f'{k} {v}' for k, v in census_rules.most_common()) or 'none'}.")
md += ["", "| Label | Tier | Meaning | PRs (all) | PRs (human) | Share of tier (human) | Merge rate when labeled (human) | Baseline (human) | AI-flagged share (human) |",
       "|---|---|---|---:|---:|---:|---:|---:|---:|"]
summary = {}
for lid, name, tier, meaning in LABELS:
    if tier == "-":
        md.append(f"| {lid} `{name}` | none | {meaning} | not measurable | | | | | |")
        summary[name] = None
        continue
    pool = tier_rows[tier]
    hpool = [r for r in pool if not r["mechanical"]]
    lab = [r for r in pool if name in r["labels"]]
    hlab = [r for r in hpool if name in r["labels"]]
    _, hdec, hm = stats(hlab)
    _, bdec, bm = stats(hpool)
    ai_share = pct(sum(r["ai"] for r in hlab), len(hlab))
    md.append(f"| {lid} `{name}` | {tier} | {meaning} | {len(lab)} | {len(hlab)} | {pct(len(hlab), len(hpool))} | "
              f"{pct(hm, hdec)} | {pct(bm, bdec)} | {ai_share} |")
    summary[name] = {"all": len(lab), "human": len(hlab), "human_decided": hdec, "human_merged": hm,
                     "human_pool": len(hpool), "human_pool_decided": bdec, "human_pool_merged": bm}

md += ["", "AI-flagged share in the human pool: tier A "
       f"{pct(sum(r['ai'] for r in tier_rows['A'] if not r['mechanical']), sum(not r['mechanical'] for r in tier_rows['A']))}, tier B "
       f"{pct(sum(r['ai'] for r in tier_rows['B'] if not r['mechanical']), sum(not r['mechanical'] for r in tier_rows['B']))}.", ""]

# per sub-signal merge rate (tier B, human PRs)
hb = [r for r in tier_rows["B"] if not r["mechanical"]]
_, bdec_b, bm_b = stats(hb)
md += ["## Sub-signals (tier B, human PRs)", "",
       f"Baseline merge rate {pct(bm_b, bdec_b)}. Below baseline means the signal is more common in PRs that were not merged.", "",
       "| Label | Sub-signal | PRs | Merge rate when present | AI-flagged share |", "|---|---|---:|---:|---:|"]
subs = defaultdict(list)
for r in hb:
    for label, keys in (r.get("signals") or {}).items():
        for k in keys:
            subs[(label, k)].append(r)
for (label, k), group in sorted(subs.items(), key=lambda x: (x[0][0], -len(x[1]))):
    _, d_, m_ = stats(group)
    md.append(f"| `{label}` | {k} | {len(group)} | {pct(m_, d_)} | {pct(sum(r['ai'] for r in group), len(group))} |")
    summary[f"{label}/{k}"] = {"prs": len(group), "decided": d_, "merged": m_}

# size thresholds on tier A (human)
ha = [r for r in tier_rows["A"] if not r["mechanical"]]
md += ["", "## Size bands (tier A, human PRs)", "", "| Files changed | PRs | Merge rate | Small-change title: PRs | Small-change title: merge rate |",
       "|---|---:|---:|---:|---:|"]
for lo, hi in ((1, 1), (2, 5), (6, 19), (20, 49), (50, 99), (100, 999), (1000, 10 ** 9)):
    band = [r for r in ha if lo <= r["files"] <= hi]
    sm = [r for r in band if small_intent(r["title"])]
    _, d1, m1 = stats(band)
    _, d2, m2 = stats(sm)
    md.append(f"| {lo}{'+' if hi > 10 ** 6 else ('' if lo == hi else f' to {hi}')} | {len(band)} | {pct(m1, d1)} | {len(sm)} | {pct(m2, d2)} |")

co = Counter()
for r in rows.values():
    if r["mechanical"]:
        continue
    ks = sorted(r["labels"])
    co[len(ks)] += 1
md += ["", "## Labels per PR (human, all tiers)", "", ", ".join(f"{k} labels: {v}" for k, v in sorted(co.items())), ""]

md += ["## Examples (human PRs, largest first; check before quoting)", ""]
for lid, name, tier, _ in LABELS:
    if tier == "-":
        continue
    ex = sorted([r for r in rows.values() if name in r["labels"] and not r["mechanical"]], key=lambda r: -r["lines"])[:6]
    if ex:
        md.append(f"### {lid} `{name}`")
        md.append("")
        for r in ex:
            md.append(f"- [#{r['pr']}]({r['url']}) {r['outcome']}{' AI' if r['ai'] else ''}: {r['title'][:90]} ({r['labels'][name][:140]})")
        md.append("")
md += ["## What the data cannot show", "",
       "- `commit-churn` needs per-commit file lists; the corpus stores only the final diff.",
       "- Hallucinated APIs and reuse of existing helpers need the repository at the PR base. Tier C (real AIV "
       "on checkouts) covers this for 627 PRs; tier B only sees code duplicated inside the PR.",
       "- Patches are not stored for every PR, and very large PRs are under-represented in tier B.",
       ""]
Path(ARGS.out_md).parent.mkdir(parents=True, exist_ok=True)
Path(ARGS.out_md).write_text("\n".join(md) + "\n", encoding="utf-8")
print("\n".join(md[:60]))
