#!/usr/bin/env python3
"""Score every stored pull-request patch under the expanded bench corpora."""
from __future__ import annotations

import importlib.util
import json
import subprocess
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
SRC = Path(r"C:\Users\Administrator\Downloads\aiv-integrity-gate-2026-10-17-92ef8fda\aiv-airflow-bench\corpus\benchmarks")
OUT = HERE / "results-all"
ROWS = OUT / "rows.jsonl"

spec = importlib.util.spec_from_file_location("e2e", HERE / "run_industry_e2e.py")
e2e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e2e)


def labels_for(project: Path) -> dict[int, dict]:
    doc = json.loads((project / "cases.json").read_text(encoding="utf-8"))
    found = {}
    for case in doc["cases"]:
        number = case.get("number", case.get("pr"))
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


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    token = subprocess.check_output(["gh", "auth", "token"], text=True).strip()
    fetch = e2e.Fetcher(token)
    seen = done()
    projects = sorted(p for p in SRC.iterdir() if (p / "prs").is_dir())
    for project in projects:
        repo = None
        known = labels_for(project)
        files = sorted((project / "prs").glob("*.json"), key=lambda p: int(p.stem))
        print(f"{project.name}: {len(files)} patches", flush=True)
        for i, path in enumerate(files, 1):
            pr = json.loads(path.read_text(encoding="utf-8"))
            number = int(pr["number"])
            repo = repo or pr.get("url", "").split("/")[3] + "/" + pr.get("url", "").split("/")[4] if pr.get("url") else project.name
            if pr.get("url"):
                parts = pr["url"].split("/")
                repo = f"{parts[3]}/{parts[4]}"
            case = dict(known.get(number) or {})
            case.update({
                "id": f"{project.name}-{number}",
                "number": number,
                "url": case.get("url") or pr.get("url"),
                "title": case.get("title") or pr.get("title"),
                "label": case.get("label") or "not_in_case_list",
                "kind": case.get("kind") or "pull_request",
                "merged": case.get("merged", pr.get("merged")),
            })
            row_id = case["id"]
            if row_id in seen:
                continue
            row = e2e.score_case(repo, case, project / "prs", fetch, e2e.JAR)
            row["id"] = row_id
            row["bench"] = project.name
            with ROWS.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(row) + "\n")
            seen.add(row_id)
            print(f"[{project.name} {i}/{len(files)}] {row_id}: {row['verdict']} {row.get('reason','')}", flush=True)
            if fetch.stopped:
                print("stopped:", fetch.stopped, flush=True)
                return
    print("finished", ROWS, flush=True)


if __name__ == "__main__":
    main()
