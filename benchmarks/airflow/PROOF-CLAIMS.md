# Proving block-rate and CI impact (how claims work)

## What you asked for

1. Fetch **all** Airflow PRs closed without merge (AIP-120 window).  
2. Label + score with AIV.  
3. Keep **per-PR provenance** (origin URL, head SHA, files materialized, AIV JSON).  
4. Live-validate on GitHub via a **dedicated bench repo** (not fighting apache/airflow CI).  
5. Turn “NOT CLAIMED — no data” into **VERIFIED** block % and **ESTIMATED** CI impact with explicit math.

## Repos

| Repo | Role |
|------|------|
| [`vaquarkhan/aiv-integrity-gate`](https://github.com/vaquarkhan/aiv-integrity-gate) | Tool + census scripts + HTML/JSON reports |
| [`vaquarkhan/aiv-airflow-bench`](https://github.com/vaquarkhan/aiv-airflow-bench) | Injected PRs for **live** AIV Actions (synthetics / controls) |
| [`vaquarkhan/airflow`](https://github.com/vaquarkhan/airflow) | Fork with AIV workflow smoke (optional) |

## Commands

```powershell
# 1) Index ALL closed-unmerged in AIP-120 window (+ merged controls)
python benchmarks/airflow/scripts/run-full-census.py index `
  --closed-cap 1000 --merged-cap 25 --replace-cases

# 2) Score every case (patch materialize = fast census; syntax off — see config-census-patch.yaml)
$env:AIV_CENSUS_MATERIALIZE = "patch"
$env:AIV_CENSUS_AIV_CONFIG = "benchmarks/airflow/.aiv/config-census-patch.yaml"
python benchmarks/airflow/scripts/run-full-census.py score --resume

# 3) Seed live bench PRs on GitHub
powershell -File benchmarks/airflow/scripts/seed-bench-repo.ps1
```

Reports: `benchmarks/airflow/reports/census-latest.html` (+ per-case `provenance.json`).

### Materialization modes

| Mode | Env | Use |
|------|-----|-----|
| `head` (default) | `AIV_CENSUS_MATERIALIZE=head` | Contents API full file @ PR head — accurate syntax, slow |
| `patch` | `AIV_CENSUS_MATERIALIZE=patch` + `AIV_CENSUS_AIV_CONFIG=.../config-census-patch.yaml` | Patch hunks only; **syntax gate disabled** to avoid reconstruction FPs |

Each `provenance.json` records `materialize_mode`, origin PR URL, title, label class, and workspace files injected.
## Claim tags (required for sharing)

| Tag | Meaning |
|-----|---------|
| **VERIFIED** | `block_rate = hard_fails / closed_unmerged_scored` from this census |
| **ESTIMATED** | `block_rate × 508 × 779 job-min` using AIP-120 published constants — **assumptions listed in the HTML** |
| **NOT CLAIMED** | Closed-unmerged = AI authorship; exact $ saved; issue-search ChatGPT counts |

### Formulas

```text
VERIFIED:
  block_rate = H / N
  where N = closed-unmerged PRs scored, H = AIV hard-fail count

ESTIMATED (same window as AIP-120):
  blocked_of_508 ≈ block_rate × 508
  job_min_avoided ≈ blocked_of_508 × 779   # AIP-120 §3.2 mean
  pct_of_508_blocked ≈ block_rate × 100
```

Until `census-latest.json` exists with full N, quote only the smaller 51-case report (`reports/latest.html`) or wait for census completion.

## Published census result (2026-09-19T19:13Z)

| Claim | Value | Tag |
|-------|-------|-----|
| Closed-unmerged scored | **596** | VERIFIED |
| Hard-blocked | **1** ([#70053](https://github.com/apache/airflow/pull/70053), `invariant`) | VERIFIED |
| Block rate | **0.17%** | VERIFIED |
| Merged-control FP | **0%** | VERIFIED |
| Synthetic recall | **100%** | VERIFIED |
| Of AIP-120 508 @ same rate | ~**0.9** PRs / ~**664** job-min | ESTIMATED |
| Objective markers in index | **0 / 597** | VERIFIED |

Materialize: `patch` + `config-census-patch.yaml` (syntax disabled). Full per-PR provenance under `reports/census-20260919T180014Z/*/provenance.json`.

Live bench: [`vaquarkhan/aiv-airflow-bench`](https://github.com/vaquarkhan/aiv-airflow-bench) PRs 1–4 synthetics/control; PRs 5–9 real Airflow closed-unmerged snapshots (incl. #70053).

## Why a separate bench repo?

Scoring hundreds of historical apache/airflow PRs as live PRs on the Airflow fork is impractical (huge tree, matrix noise). The bench repo injects **fixtures as PRs** so Actions proves AIV FAIL/PASS wiring; the **census** proves rates against real Airflow PR heads via API materialization + local `aiv-cli`.
