# Copyright 2026 Vaquar Khan
# SPDX-License-Identifier: Apache-2.0
"""Mine change-shape patterns from every stored patch in aiv-airflow-bench and compare by outcome.

Facts follow docs/AI-integration/CHANGE-POLICY-ENGINE-DESIGN.md section 4, approximated from GitHub
patch text (no repository checkout, so reuse against existing code is not measured here).

    python benchmarks/airflow/scripts/mine-change-patterns.py [--bench ../aiv-airflow-bench] [--out <dir>]

Prints per-signal flag rates for merged vs closed and AI-flagged vs not, and merge rate per signal.
With --out, also writes change-pattern-examples.json and change-pattern-rows.json there.
Results are recorded in docs/AI-integration/MAINTAINER-PAIN-AND-PR-PATTERNS-ANALYSIS.md.
"""
import argparse
import fnmatch
import hashlib
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

_ap = argparse.ArgumentParser()
_ap.add_argument("--bench", default=str(Path(__file__).resolve().parents[4] / "aiv-airflow-bench"))
_ap.add_argument("--out", default=None)
ARGS = _ap.parse_args()
C = Path(ARGS.bench) / "corpus"
SOURCES = {"airflow-ai-year": C / "ai-year" / "prs", "airflow-e2e": C / "e2e-proof" / "prs"}
for p in ["cosmic", "curl", "flink", "ghostty", "godot", "kafka"]:
    SOURCES[p] = C / "benchmarks" / p / "prs"

CATS = [
    ("lockfile", ["*package-lock.json", "*yarn.lock", "*pnpm-lock.yaml", "*uv.lock", "*poetry.lock", "*Cargo.lock",
                  "*go.sum", "*Gemfile.lock", "*composer.lock", "*.lock"]),
    ("generated", ["*generated*", "*_pb2.py", "*.min.js", "*.snap", "*/dist/*", "*.svg"]),
    ("vendored", ["vendor/*", "*/vendor/*", "third_party/*", "*/third_party/*", "thirdparty/*"]),
    ("ci", [".github/*", ".gitlab-ci*", "*Jenkinsfile", ".circleci/*", "azure-pipelines*", "*.github/workflows/*"]),
    ("ai-instruction", ["*AGENTS.md", "*CLAUDE.md", "*GEMINI.md", ".github/copilot-instructions.md", ".github/instructions/*",
                        ".cursor/*", ".cursorrules", ".kiro/*", ".github/skills/*"]),
    ("dependency-manifest", ["*requirements*.txt", "*pyproject.toml", "*setup.py", "*setup.cfg", "*package.json",
                             "*pom.xml", "*build.gradle*", "*Cargo.toml", "*go.mod", "*Gemfile", "*.csproj"]),
    ("migration", ["*migrations/*", "*alembic/*"]),
    ("test", ["*test/*", "*tests/*", "*__tests__/*", "*test_*.py", "*_test.py", "*_test.go", "*Test.java", "*Tests.java",
              "*.test.*", "*.spec.*", "*IT.java", "*/testing/*", "*testdata/*"]),
    ("docs", ["*.md", "*.rst", "*.txt", "docs/*", "*/docs/*", "*.adoc", "*newsfragments/*", "*CHANGELOG*", "*RELEASE_NOTES*"]),
    ("config", ["*.yaml", "*.yml", "*.json", "*.toml", "*.ini", "*.cfg", "*.properties", "*.conf", "*.xml"]),
    ("binary", ["*.png", "*.jpg", "*.gif", "*.ico", "*.pdf", "*.jar", "*.zip", "*.woff*", "*.ttf"]),
]
NON_REVIEWABLE = {"lockfile", "generated", "vendored", "binary"}
SMALL_INTENT = re.compile(r"(?i)\b(typo|fix(es|ed)?|bump|minor|small|tweak|nit|doc(s|umentation)?|spelling|grammar)\b")
COMMENT_LINE = re.compile(r"^\s*(#|//|/\*|\*|--|<!--)")
CODEISH_COMMENT = re.compile(r"^\s*(#|//)\s*[\w.]+\s*(\(|=|\.|\[)[^\n]*[)\];:]?\s*$")
NARRATING = re.compile(r"(?i)^\s*(#|//)\s*(increment|initialize|initialise|loop (over|through)|check if|return the|"
                       r"create (a|an|the) new|set the|get the|call the|import the|define (a|the))\b")
BROAD_EXCEPT = re.compile(r"^\s*(except\s*(Exception)?\s*:|catch\s*\(\s*(Exception|Throwable|\.\.\.)\b)")
DEF = re.compile(r"^\s*(def|async def)\s+([A-Za-z_]\w*)\s*\(|^\s*(public|private|protected|static|\s)*[\w<>\[\], ]+\s+([a-z]\w*)\s*\([^;]*\)\s*(\{|throws)|^\s*func\s+(\([^)]*\)\s*)?([A-Za-z_]\w*)\s*\(|^\s*(export\s+)?(async\s+)?function\s+([A-Za-z_]\w*)\s*\(")


def category(path):
    p = path.replace("\\", "/")
    for name, globs in CATS:
        if any(fnmatch.fnmatch(p, g) or fnmatch.fnmatch("/" + p, g) for g in globs):
            return name
    return "source"


def added_removed(patch):
    add, rem = [], []
    for line in (patch or "").split("\n"):
        if line.startswith("+") and not line.startswith("+++"):
            add.append(line[1:])
        elif line.startswith("-") and not line.startswith("---"):
            rem.append(line[1:])
    return add, rem


def norm(line):
    return re.sub(r"\s+", " ", line).strip()


def norm_ident(line):
    s = norm(line)
    s = re.sub(r"(['\"]).*?\1", "S", s)
    s = re.sub(r"\b\d+(\.\d+)?\b", "N", s)
    return re.sub(r"\b[A-Za-z_]\w*\b", "I", s)


def windows(lines, k=6):
    sig = [l for l in (norm(x) for x in lines) if len(l) > 3 and l not in "{}()[];" and not COMMENT_LINE.match(l)
           and not l.startswith(("import ", "from ", "package "))]
    return {hashlib.sha1("\n".join(sig[i:i + k]).encode()).hexdigest() for i in range(len(sig) - k + 1)}


def facts(pr):
    files = pr.get("files") or []
    f = Counter()
    cats = Counter()
    rev_files, rev_lines, src_lines, test_lines = 0, 0, 0, 0
    ws_only, rename_only, comment_only = 0, 0, 0
    commented_code, narrating, broad_except, defs = 0, 0, 0, []
    blocks = defaultdict(set)
    rblocks = defaultdict(set)
    for fl in files:
        path = fl.get("filename") or ""
        cat = category(path)
        cats[cat] += 1
        add, rem = added_removed(fl.get("patch"))
        status = fl.get("status")
        is_ws = bool(add or rem) and sorted(norm(x) for x in add if norm(x)) == sorted(norm(x) for x in rem if norm(x))
        is_rename_only = status == "renamed" and not add and not rem
        ws_only += is_ws
        rename_only += is_rename_only
        if add and all(COMMENT_LINE.match(x) or not x.strip() for x in add) and cat == "source":
            comment_only += 1
        reviewable = cat not in NON_REVIEWABLE and not is_ws and not is_rename_only
        changes = (fl.get("additions") or 0) + (fl.get("deletions") or 0)
        if reviewable:
            rev_files += 1
            rev_lines += changes
            if cat == "source":
                src_lines += changes
            if cat == "test":
                test_lines += changes
        if cat in ("source", "test"):
            commented_code += sum(1 for x in add if CODEISH_COMMENT.match(x))
            narrating += sum(1 for x in add if NARRATING.match(x))
            broad_except += sum(1 for x in add if BROAD_EXCEPT.match(x))
            for x in add:
                m = DEF.match(x)
                if m:
                    name = next((g for g in (m.group(2), m.group(4), m.group(7), m.group(10)) if g), None)
                    if name and name not in ("if", "for", "while", "switch", "return", "catch", "new"):
                        defs.append(name)
            for w in windows(add):
                blocks[w].add(path)
            ident_lines = [norm_ident(x) for x in add]
            for w in windows(ident_lines):
                rblocks[w].add(path)
    intra = sum(1 for v in blocks.values() if len(v) > 1)
    intra_renamed = sum(1 for v in rblocks.values() if len(v) > 1)
    dup_defs = sum(c - 1 for c in Counter(defs).values() if c > 1)
    reviewable_cats = {c for c in cats if c not in NON_REVIEWABLE}
    title = pr.get("title") or ""
    return {
        "files": pr.get("changed_files") or len(files),
        "files_listed": len(files),
        "lines": (pr.get("additions") or 0) + (pr.get("deletions") or 0),
        "rev_files": rev_files, "rev_lines": rev_lines, "src_lines": src_lines, "test_lines": test_lines,
        "non_reviewable_files": sum(cats[c] for c in NON_REVIEWABLE),
        "ws_only": ws_only, "rename_only": rename_only, "comment_only_src": comment_only,
        "cats_mixed": len(reviewable_cats), "has_ci": cats["ci"] > 0, "has_deps": cats["dependency-manifest"] > 0,
        "has_ai_instr": cats["ai-instruction"] > 0, "has_lock": cats["lockfile"] > 0,
        "commented_code": commented_code, "narrating": narrating, "broad_except": broad_except,
        "intra_dup": intra, "intra_dup_renamed": intra_renamed, "dup_defs": dup_defs,
        "small_intent": bool(SMALL_INTENT.search(title)),
        "truncated": bool(pr.get("files_truncated") or pr.get("too_big") or any(x.get("patch_truncated") or x.get("patch_omitted") for x in files)),
    }


def outcome(pr):
    if pr.get("merged"):
        return "merged"
    return "closed" if (pr.get("state") or "").lower() == "closed" else "open"


def disclosed(pr):
    d = pr.get("disclosure") or {}
    fl = pr.get("flag") or {}
    return bool(d.get("disclosed") or d.get("generated_by") or fl.get("ai_related"))


rows = []
seen = set()
for src, d in SOURCES.items():
    for f in sorted(d.glob("*.json")):
        try:
            pr = json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            continue
        repo = "airflow" if src.startswith("airflow") else src
        key = (repo, pr.get("number"))
        if key in seen or not pr.get("files"):
            continue
        seen.add(key)
        r = facts(pr)
        r.update({"repo": repo, "pr": pr.get("number"), "outcome": outcome(pr), "ai": disclosed(pr), "title": pr.get("title")})
        rows.append(r)

print("PRs with patch data:", len(rows), Counter(r["repo"] for r in rows))
print("outcomes:", Counter(r["outcome"] for r in rows), "AI-flagged:", sum(r["ai"] for r in rows))
print("truncated patches:", sum(r["truncated"] for r in rows))

SIGNALS = {
    "rev_files>=20": lambda r: r["rev_files"] >= 20,
    "rev_files>=50": lambda r: r["rev_files"] >= 50,
    "rev_lines>=1000": lambda r: r["rev_lines"] >= 1000,
    "small_intent & rev_files>10": lambda r: r["small_intent"] and r["rev_files"] > 10,
    "cats_mixed>=3": lambda r: r["cats_mixed"] >= 3,
    "ci+source mixed": lambda r: r["has_ci"] and r["src_lines"] > 0,
    "deps changed": lambda r: r["has_deps"],
    "lockfile changed": lambda r: r["has_lock"],
    "ws_only>=1": lambda r: r["ws_only"] >= 1,
    "ws_only>=5": lambda r: r["ws_only"] >= 5,
    "non_reviewable>=1": lambda r: r["non_reviewable_files"] >= 1,
    "intra_dup>=1": lambda r: r["intra_dup"] >= 1,
    "intra_dup_renamed>=2": lambda r: r["intra_dup_renamed"] >= 2,
    "dup_defs>=1": lambda r: r["dup_defs"] >= 1,
    "commented_code>=3": lambda r: r["commented_code"] >= 3,
    "narrating>=3": lambda r: r["narrating"] >= 3,
    "broad_except>=2": lambda r: r["broad_except"] >= 2,
    "comment_only_src>=1": lambda r: r["comment_only_src"] >= 1,
    "src>=200 & no tests": lambda r: r["src_lines"] >= 200 and r["test_lines"] == 0,
    "ai-instruction touched": lambda r: r["has_ai_instr"],
}


def rate(sub, fn):
    return (sum(1 for r in sub if fn(r)), len(sub))


groups = {
    "merged": [r for r in rows if r["outcome"] == "merged"],
    "closed": [r for r in rows if r["outcome"] == "closed"],
    "ai": [r for r in rows if r["ai"]],
    "not-ai": [r for r in rows if not r["ai"]],
}
print(f"\n{'signal':30} {'merged':>14} {'closed':>14} {'lift':>5} {'AI':>14} {'not AI':>14}")
summary = {}
for name, fn in SIGNALS.items():
    vals = {g: rate(sub, fn) for g, sub in groups.items()}
    pm = vals["merged"][0] / max(1, vals["merged"][1])
    pc = vals["closed"][0] / max(1, vals["closed"][1])
    lift = pc / pm if pm else float("inf")
    cell = lambda v: f"{v[0]:4}/{v[1]:<4} {100 * v[0] / max(1, v[1]):4.1f}%"
    print(f"{name:30} {cell(vals['merged']):>14} {cell(vals['closed']):>14} {lift:5.2f} {cell(vals['ai']):>14} {cell(vals['not-ai']):>14}")
    summary[name] = {g: v for g, v in vals.items()}

# merge rate per signal (closed + merged only)
print(f"\n{'signal':30} {'flagged decided':>15} {'merge rate':>10}   baseline")
decided = groups["merged"] + groups["closed"]
base = len(groups["merged"]) / max(1, len(decided))
for name, fn in SIGNALS.items():
    fl = [r for r in decided if fn(r)]
    if fl:
        mr = sum(1 for r in fl if r["outcome"] == "merged") / len(fl)
        print(f"{name:30} {len(fl):15} {100 * mr:9.1f}%   {100 * base:.1f}%")

# examples for manual review
ex = defaultdict(list)
for r in rows:
    for name, fn in SIGNALS.items():
        if fn(r) and len(ex[name]) < 6:
            ex[name].append(f"{r['repo']}#{r['pr']} {r['outcome']} files={r['files']} rev={r['rev_files']} :: {(r['title'] or '')[:70]}")
if ARGS.out:
    out = Path(ARGS.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "change-pattern-examples.json").write_text(json.dumps(ex, indent=1), encoding="utf-8")
    (out / "change-pattern-rows.json").write_text(json.dumps(rows), encoding="utf-8")
    print(f"\nexamples and rows written to {out}")
