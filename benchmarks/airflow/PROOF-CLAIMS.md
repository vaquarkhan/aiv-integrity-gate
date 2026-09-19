# Proving product value (claim policy)

## Primary proof: labeled 50-case bench

Do **not** lead with the Airflow closed-unmerged census **0.17%** figure for product value.
That census is unlabeled volume context (closed ≠ AI slop).

| Cohort | n | Expect |
|--------|---|--------|
| Proper (clean controls) | 5 | hard PASS |
| AI-slop (objective markers) | 30 | hard FAIL |
| Mixed | 15 | 5 cohesion advisory, 5 soft PASS, 5 clean+artifact FAIL |

**Live GitHub:** [`vaquarkhan/aiv-airflow-bench`](https://github.com/vaquarkhan/aiv-airflow-bench) — one PR per labeled case.  
**Local report:** [`reports/labeled-latest.html`](reports/labeled-latest.html)

### How to reproduce

```powershell
python benchmarks/airflow/scripts/generate-labeled-corpus.py
mvn -pl aiv-cli -am package -DskipTests
python benchmarks/airflow/scripts/run-labeled-benchmark.py
python benchmarks/airflow/scripts/seed-labeled-bench.py --close-old
```

### Published labeled result (local, VERIFIED)

From `reports/labeled-latest.json`:

| Metric | Value |
|--------|-------|
| Hard-gate recall (block labeled slop) | **100%** (35/35) |
| False-positive rate (on expect-pass) | **0%** (0/15) |
| Precision | **100%** |
| Cohesion advisory hit rate | **100%** (5/5 expected) |

Airflow census **0.17%** remains secondary volume context only — not the product-value headline.

| Tag | Meaning |
|-----|---------|
| **VERIFIED** | From labeled-50 (precision / recall / FP) or checked-in report JSON |
| **ESTIMATED** | Extrapolation using AIP-120 constants — assumptions required |
| **NOT CLAIMED** | Closed-unmerged = AI authorship; census % as “AI slop catch rate” |

## Secondary: Airflow real-data census

Index/score real closed-unmerged PRs for volume context only (`reports/census-latest.html`).
Use after the labeled bench shows the tool works on known ground truth.

## Repos

| Repo | Role |
|------|------|
| [`vaquarkhan/aiv-integrity-gate`](https://github.com/vaquarkhan/aiv-integrity-gate) | Product + fixtures + reports |
| [`vaquarkhan/aiv-airflow-bench`](https://github.com/vaquarkhan/aiv-airflow-bench) | Live Actions on labeled PRs |
| [`vaquarkhan/airflow`](https://github.com/vaquarkhan/airflow) | Optional fork smoke |
