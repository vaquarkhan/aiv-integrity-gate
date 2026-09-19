#!/usr/bin/env python3
"""
Seed vaquarkhan/aiv-airflow-bench with labeled PRs from cases-labeled-50.json.

Updates main with current .aiv + workflow, then opens one PR per case.
Closes nothing automatically; use --close-old to close prior open PRs first.
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BENCH = ROOT / "benchmarks" / "airflow"
CORPUS = BENCH / "corpus" / "cases-labeled-50.json"
REPO = "vaquarkhan/aiv-airflow-bench"


def run(cmd: list[str], cwd: Path | None = None, check: bool = True) -> str:
    r = subprocess.run(cmd, cwd=cwd, text=True, encoding="utf-8", errors="replace", capture_output=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"cmd failed {cmd}: {r.stderr or r.stdout}")
    return (r.stdout or "").strip()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=REPO)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--close-old", action="store_true")
    ap.add_argument("--start-at", type=int, default=0, help="Skip first N cases")
    args = ap.parse_args()

    corpus = json.loads(CORPUS.read_text(encoding="utf-8"))
    cases = corpus["cases"]
    if args.start_at:
        cases = cases[args.start_at :]
    if args.limit:
        cases = cases[: args.limit]

    if args.close_old:
        prs = json.loads(run(["gh", "pr", "list", "--repo", args.repo, "--state", "open", "--json", "number", "--limit", "100"]))
        for pr in prs:
            n = pr["number"]
            print(f"Closing old PR #{n}", flush=True)
            run(["gh", "pr", "close", str(n), "--repo", args.repo, "--comment", "Superseded by labeled-50 reseeds"], check=False)

    tmp = Path(tempfile.mkdtemp(prefix="aiv-bench-seed-"))
    try:
        run(["gh", "repo", "clone", args.repo, str(tmp), "--", "--depth", "1"])
        # refresh main config
        run(["git", "checkout", "main"], cwd=tmp)
        if (tmp / ".aiv").exists():
            shutil.rmtree(tmp / ".aiv")
        shutil.copytree(BENCH / ".aiv", tmp / ".aiv")
        wf = tmp / ".github" / "workflows"
        wf.mkdir(parents=True, exist_ok=True)
        shutil.copy2(BENCH / "workflows" / "aiv.yml", wf / "aiv.yml")
        readme = tmp / "README.md"
        readme.write_text(
            "# aiv-airflow-bench\n\n"
            "Labeled product-value bench for AIV Integrity Gate.\n\n"
            "- **50 PRs**: 5 proper / 30 ai-slop / 15 mixed (see gate repo `corpus/cases-labeled-50.json`)\n"
            "- Local report: `benchmarks/airflow/reports/labeled-latest.html` in aiv-integrity-gate\n"
            "- This is **not** apache/airflow\n",
            encoding="utf-8",
        )
        run(["git", "config", "user.email", "bench@aiv.local"], cwd=tmp)
        run(["git", "config", "user.name", "AIV Bench"], cwd=tmp)
        run(["git", "add", "-A"], cwd=tmp)
        run(["git", "commit", "-q", "-m", "chore: refresh .aiv + workflow for labeled-50 bench"], cwd=tmp, check=False)
        run(["git", "push", "origin", "main"], cwd=tmp)

        for i, case in enumerate(cases, 1):
            cid = case["id"]
            branch = f"labeled/{cid}"
            title = case.get("title") or cid
            lab = case.get("label") or {}
            print(f"[{i}/{len(cases)}] {cid} -> {branch}", flush=True)
            run(["git", "checkout", "main", "-q"], cwd=tmp)
            run(["git", "checkout", "-B", branch], cwd=tmp)
            # clean previous inject
            for junk in ["providers", ".github/workflows/wf-", "scripts"]:
                pass
            # remove prior case files except .aiv and workflow
            for p in list((tmp / "providers").rglob("*")) if (tmp / "providers").exists() else []:
                if p.is_file():
                    p.unlink()
            inject_tree = case.get("inject_tree")
            if inject_tree:
                src_tree = BENCH / inject_tree
                for src in src_tree.rglob("*"):
                    if not src.is_file():
                        continue
                    dest = tmp / src.relative_to(src_tree)
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(src, dest)
            else:
                paths = case.get("paths") or [case["path"]]
                for p in paths:
                    src = BENCH / p
                    dest = tmp / "providers" / "bench" / Path(p).name
                    # keep stable unique path per case
                    dest = tmp / "providers" / "bench" / cid / Path(p).name
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(src, dest)
            prov = tmp / "provenance" / cid
            prov.mkdir(parents=True, exist_ok=True)
            (prov / "label.json").write_text(json.dumps({"case": case}, indent=2) + "\n", encoding="utf-8")
            run(["git", "add", "-A"], cwd=tmp)
            run(["git", "commit", "-q", "-m", title[:70]], cwd=tmp)
            run(["git", "push", "-u", "origin", branch, "--force"], cwd=tmp)
            existing = run(
                ["gh", "pr", "list", "--repo", args.repo, "--head", branch, "--json", "number", "-q", ".[0].number"],
                check=False,
            )
            if not existing:
                body = (
                    f"## Labeled bench case `{cid}`\n\n"
                    f"- class: `{lab.get('class')}`\n"
                    f"- subtype: `{lab.get('subtype')}`\n"
                    f"- expect_hard_fail: `{lab.get('expect_hard_fail')}`\n"
                    f"- expect_advisory: `{lab.get('expect_advisory')}`\n"
                    f"- notes: {lab.get('notes')}\n\n"
                    f"Ground truth from aiv-integrity-gate `corpus/cases-labeled-50.json`.\n"
                )
                run(
                    [
                        "gh", "pr", "create", "--repo", args.repo,
                        "--base", "main", "--head", branch,
                        "--title", title[:80],
                        "--body", body,
                    ],
                    cwd=tmp,
                )
            time.sleep(0.4)
        print(run(["gh", "pr", "list", "--repo", args.repo, "--limit", "60"]))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
