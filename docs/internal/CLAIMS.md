# Claims policy

What AIV may say in docs, README, sales, and benchmarks.

## Say (defensible)

- Deterministic, air-gapped integrity gate for **objectively broken** AI/agent output (won't-parse, conflict / SEARCH-REPLACE paste, elision markers, tautology tests, hallucinated imports vs lockfile).
- **Labeled 50-case bench:** 100% recall, 100% precision, 0% false positives (VERIFIED in `benchmarks/airflow/reports/labeled-latest.json`).
- **Merged controls:** 0% hard-gate FP in published Airflow control samples (tag VERIFIED where cited).
- Hard path fires only on reproducible defects; when uncertain, **pass**.
- Soft signals (density, cohesion) are **advisory / labels**, not the sole merge blocker.
- Best install path: **pre-commit / agent-output**, with CI as backstop.

## Never say

- "AIV catches AI slop" (unqualified).
- Any **measured** CI-cost % or dollar savings for Airflow / AIP-120.
- "Closed-unmerged PRs are AI slop."
- "AIV solves AIP-120" or replaces issue-gating / fork-CI.
- "Zero FP on all Airflow PRs" (materialize/config variants differ).
- That density/cohesion **judge PR value** as a hard gate.

## Punch lines (safe)

- "Block broken AI paste, never a good PR."
- "Catch the paste, not the person."
- "The commit-time tripwire for objectively broken AI code."
- "Zero-false-positive floor for the age of AI pull requests."

## Internal proof notes

- [../../benchmarks/airflow/internal/PROOF-CLAIMS.md](../../benchmarks/airflow/internal/PROOF-CLAIMS.md)
- Pages (optional public bench): https://vaquarkhan.github.io/aiv-airflow-bench/
