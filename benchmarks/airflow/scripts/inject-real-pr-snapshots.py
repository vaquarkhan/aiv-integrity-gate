#!/usr/bin/env python3
"""Inject real apache/airflow closed-unmerged PR snapshots into aiv-airflow-bench as PRs."""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CASES = ROOT / "benchmarks" / "airflow" / "corpus" / "cases-census.json"
DEFAULT_REPO = "vaquarkhan/aiv-airflow-bench"


def run(cmd: list[str], cwd: Path | None = None) -> str:
    return subprocess.check_output(cmd, cwd=cwd, text=True, encoding="utf-8", errors="replace").strip()


def run_bytes(cmd: list[str], cwd: Path | None = None) -> bytes:
    return subprocess.check_output(cmd, cwd=cwd)


def content_from_patch(patch: str | None) -> str | None:
    if not patch:
        return None
    lines: list[str] = []
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


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--count", type=int, default=5)
    p.add_argument("--repo", default=DEFAULT_REPO)
    p.add_argument("--include-blocked", action="store_true", help="Prefer hard-blocked census PRs first")
    args = p.parse_args()

    corpus = json.loads(CASES.read_text(encoding="utf-8"))
    closed = [
        c
        for c in corpus.get("cases", [])
        if c.get("source") == "github" and not (c.get("pr_meta") or {}).get("merged")
    ]

    preferred: list[dict] = []
    if args.include_blocked:
        latest = ROOT / "benchmarks" / "airflow" / "reports" / "census-latest.json"
        if latest.is_file():
            summary = json.loads(latest.read_text(encoding="utf-8"))
            blocked_ids = {
                r["id"]
                for r in summary.get("cases", [])
                if (r.get("outcome") or {}).get("hard_fail") and r.get("source") == "github"
            }
            preferred = [c for c in closed if c.get("id") in blocked_ids]

    selected = preferred + [c for c in closed if c not in preferred]
    selected = selected[: args.count]

    tmp = Path(tempfile.mkdtemp(prefix="aiv-bench-inject-"))
    try:
        run(["gh", "repo", "clone", args.repo, str(tmp), "--", "--depth", "1"])
        for c in selected:
            n = int(c["pr"])
            branch = f"snapshot/airflow-pr-{n}"
            print(f"Injecting {c.get('url')} as {branch}", flush=True)
            run(["git", "checkout", "main", "-q"], cwd=tmp)
            run(["git", "checkout", "-B", branch], cwd=tmp)
            prov = tmp / "provenance" / f"airflow-pr-{n}"
            prov.mkdir(parents=True, exist_ok=True)
            (prov / "origin.json").write_text(
                json.dumps(
                    {
                        "origin_url": c.get("url"),
                        "title": c.get("title"),
                        "label": c.get("label"),
                        "discovery": c.get("discovery"),
                        "pr_meta": c.get("pr_meta"),
                        "note": "Snapshot for live AIV CI; patch-reconstructed files.",
                    },
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )
            raw = run_bytes(["gh", "api", f"repos/apache/airflow/pulls/{n}/files", "--paginate"])
            files = json.loads(raw.decode("utf-8", errors="replace"))
            if not isinstance(files, list):
                files = [files]
            written = 0
            for f in files:
                if f.get("status") == "removed" or not f.get("filename"):
                    continue
                if str(f["filename"]).endswith(".md"):
                    continue
                text = content_from_patch(f.get("patch"))
                if text is None:
                    continue
                dest = tmp / "providers" / "snapshot" / f"pr-{n}" / f["filename"]
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_text(text, encoding="utf-8", errors="replace")
                written += 1
                if written >= 12:
                    break
            if written == 0:
                (prov / "NO_FILES.txt").write_text("no patchable files\n", encoding="utf-8")
            run(["git", "add", "-A"], cwd=tmp)
            run(
                ["git", "commit", "-q", "-m", f"snapshot: apache/airflow#{n} (closed-unmerged sample)"],
                cwd=tmp,
            )
            run(["git", "push", "-u", "origin", branch, "--force"], cwd=tmp)
            existing = subprocess.run(
                ["gh", "pr", "list", "--repo", args.repo, "--head", branch, "--json", "number", "-q", ".[0].number"],
                text=True,
                capture_output=True,
            ).stdout.strip()
            if not existing:
                body = (
                    f"Origin: {c.get('url')}\n"
                    f"Label: {(c.get('label') or {}).get('class')}\n"
                    "Injected for live AIV Actions validation (not an Airflow contribution)."
                )
                run(
                    [
                        "gh",
                        "pr",
                        "create",
                        "--repo",
                        args.repo,
                        "--base",
                        "main",
                        "--head",
                        branch,
                        "--title",
                        f"snapshot: airflow#{n}",
                        "--body",
                        body,
                    ],
                    cwd=tmp,
                )
        print(f"Done: https://github.com/{args.repo}")
        print(run(["gh", "pr", "list", "--repo", args.repo, "--limit", "20"]))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
