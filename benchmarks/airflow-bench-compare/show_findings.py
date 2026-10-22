#!/usr/bin/env python3
"""Prints hard findings (excluding syntax) from the new-build reports for manual FP review."""
import json
import sys
from pathlib import Path

reports = Path(sys.argv[1] if len(sys.argv) > 1 else "benchmarks/airflow-bench-compare/results/reports")
gate_filter = sys.argv[2] if len(sys.argv) > 2 else None
for p in sorted(reports.glob("*.new.json")):
    d = json.loads(p.read_text(encoding="utf-8"))
    for g in d.get("gates", []):
        if g["passed"] or g["id"] == "syntax":
            continue
        if gate_filter and g["id"] != gate_filter:
            continue
        kind = "HARD" if g["blocks_ci"] else "soft"
        for f in g.get("findings", []):
            print(f"{p.name.replace('.new.json','')} | {kind} {g['id']} | {f['rule_id']} | {f['file']}:{f['start_line']} | {f['message'][:160]}")
