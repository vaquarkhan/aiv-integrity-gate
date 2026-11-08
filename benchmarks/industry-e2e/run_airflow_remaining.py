#!/usr/bin/env python3
"""Score every stored Airflow patch that the 555-case sample did not include."""
from __future__ import annotations

import importlib.util
import json
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
SRC = Path(
    r"C:\Users\Administrator\Downloads\aiv-integrity-gate-2026-10-17-92ef8fda"
    r"\aiv-airflow-bench\corpus\e2e-proof"
)
CASES = HERE / "results" / "cases.json"
OUT = HERE / "results-airflow-extra"
ROWS = OUT / "rows.jsonl"

spec = importlib.util.spec_from_file_location("e2e", HERE / "run_industry_e2e.py")
e2e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e2e)


def scored_numbers() -> set[int]:
    rows = json.loads(CASES.read_text(encoding="utf-8"))
    return {int(r["pr"]) for r in rows if r.get("project_repo") == "apache/airflow"}


def already() -> set[int]:
    if not ROWS.is_file():
        return set()
    done = set()
    for line in ROWS.read_text(encoding="utf-8").splitlines():
        if line.strip():
            done.add(int(json.loads(line)["pr"]))
    return done


def labels() -> dict[int, dict]:
    doc = json.loads((SRC / "cases.json").read_text(encoding="utf-8"))
    found = {}
    for case in doc["cases"]:
        number = case.get("pr", case.get("number"))
        if number is not None:
            found[int(number)] = case
    return found


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    token = subprocess.check_output(["gh", "auth", "token"], text=True).strip()
    fetch = e2e.Fetcher(token)
    known = labels()
    skip = scored_numbers() | already()
    files = sorted((SRC / "prs").glob("*.json"), key=lambda p: int(p.stem))
    todo = [p for p in files if int(p.stem) not in skip]
    print(f"stored {len(files)} already scored {len(skip)} remaining {len(todo)}", flush=True)
    for i, path in enumerate(todo, 1):
        pr = json.loads(path.read_text(encoding="utf-8"))
        number = int(pr["number"])
        case = dict(known.get(number) or {})
        case.update({
            "id": f"airflow-extra-{number}",
            "pr": number,
            "url": case.get("url") or pr.get("url"),
            "title": case.get("title") or pr.get("title"),
            "label": case.get("label") or "stored_not_in_555",
            "kind": "pull_request",
            "merged": case.get("merged", pr.get("merged")),
        })
        row = e2e.score_case("apache/airflow", case, SRC / "prs", fetch, e2e.JAR)
        row["id"] = case["id"]
        row["bench"] = "airflow-extra"
        with ROWS.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(row) + "\n")
        print(f"[{i}/{len(todo)}] {number}: {row['verdict']} {row.get('reason','')}", flush=True)
        if fetch.stopped:
            print("stopped:", fetch.stopped, flush=True)
            return
    print("finished", ROWS, flush=True)


if __name__ == "__main__":
    main()
