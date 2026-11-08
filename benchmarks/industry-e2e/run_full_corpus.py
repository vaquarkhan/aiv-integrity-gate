#!/usr/bin/env python3
"""Score every stored pull-request patch file. Not the case-list sample."""
from __future__ import annotations

import importlib.util
import json
import subprocess
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
SRC = Path(
    r"C:\Users\Administrator\Downloads\aiv-integrity-gate-2026-10-17-92ef8fda"
    r"\aiv-airflow-bench\corpus"
)
OUT = HERE / "results-full"
ROWS = OUT / "rows.jsonl"

spec = importlib.util.spec_from_file_location("e2e", HERE / "run_industry_e2e.py")
e2e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e2e)


def labels_for(project: Path) -> dict[int, dict]:
    path = project / "cases.json"
    if not path.is_file():
        return {}
    doc = json.loads(path.read_text(encoding="utf-8"))
    found = {}
    for case in doc.get("cases") or []:
        number = case.get("pr", case.get("number"))
        if number is not None:
            found[int(number)] = case
    return found


def done() -> set[str]:
    if not ROWS.is_file():
        return set()
    seen = set()
    for line in ROWS.read_text(encoding="utf-8").splitlines():
        if line.strip():
            row = json.loads(line)
            seen.add(row["id"])
    return seen


def projects() -> list[Path]:
    found = sorted({p.parent.parent for p in SRC.rglob("prs/*.json")})
    return found


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    token = subprocess.check_output(["gh", "auth", "token"], text=True).strip()
    fetch = e2e.Fetcher(token)
    seen = done()
    folders = projects()
    total = sum(len(list((p / "prs").glob("*.json"))) for p in folders)
    print(f"folders {len(folders)} stored patches {total} already {len(seen)}", flush=True)
    for project in folders:
        known = labels_for(project)
        files = sorted((project / "prs").glob("*.json"), key=lambda p: int(p.stem))
        bench = project.name if project.name != "e2e-proof" else "airflow"
        print(f"{project.relative_to(SRC)}: {len(files)}", flush=True)
        for i, path in enumerate(files, 1):
            pr = json.loads(path.read_text(encoding="utf-8"))
            number = int(pr["number"])
            url = pr.get("url") or ""
            parts = url.split("/")
            repo = f"{parts[3]}/{parts[4]}" if len(parts) > 4 else bench
            row_id = f"{bench}-{number}"
            if row_id in seen:
                continue
            case = dict(known.get(number) or {})
            case.update({
                "id": row_id,
                "pr": number,
                "url": case.get("url") or url,
                "title": case.get("title") or pr.get("title"),
                "label": case.get("label") or "stored_patch",
                "kind": case.get("kind") or "pull_request",
                "merged": case.get("merged", pr.get("merged")),
            })
            row = e2e.score_case(repo, case, project / "prs", fetch, e2e.JAR)
            row["id"] = row_id
            row["bench"] = bench
            with ROWS.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(row) + "\n")
            seen.add(row_id)
            if i % 25 == 0 or row.get("verdict") == "BLOCK":
                print(f"[{bench} {i}/{len(files)}] {row_id}: {row['verdict']} {row.get('reason','')}", flush=True)
            if fetch.stopped:
                print("stopped:", fetch.stopped, flush=True)
                return
    counts = Counter()
    for line in ROWS.read_text(encoding="utf-8").splitlines():
        if line.strip():
            counts[json.loads(line).get("verdict")] += 1
    print("finished", dict(counts), "n", sum(counts.values()), ROWS, flush=True)


if __name__ == "__main__":
    main()
