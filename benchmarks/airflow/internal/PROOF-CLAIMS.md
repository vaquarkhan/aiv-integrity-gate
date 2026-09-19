# Proving product value (claim policy)

**Product story (internal):** tripwire for objectively broken agent/code paste, proven on the **labeled 50**. Airflow abandoned/census = base-rate study only. See [docs/internal/CLAIMS.md](../../../docs/internal/CLAIMS.md).

## How to read this

| Layer | What it proves | Artifact |
|-------|----------------|----------|
| **1. Labeled 50 fixtures** | Tool precision/recall on known ground truth (**primary**) | [`labeled-latest.html`](reports/labeled-latest.html) |
| **2. Abandoned Airflow labeling** | Most abandoned != marker-style breakage; labels + catch rate | [`abandoned-labeled-latest.html`](reports/abandoned-labeled-latest.html) |
| **3. Live bench recreation** | Same PRs under AIV Actions | [`vaquarkhan/aiv-airflow-bench`](https://github.com/vaquarkhan/aiv-airflow-bench) |
| **Public Pages** | Shareable report + raw JSON | [vaquarkhan.github.io/aiv-airflow-bench](https://vaquarkhan.github.io/aiv-airflow-bench/) |
| **Master** | Combined product report | [`product-benchmark-latest.html`](reports/product-benchmark-latest.html) |

## 1) Labeled 50-case fixtures (primary)

| Cohort | n | Expect |
|--------|---|--------|
| Proper | 5 | PASS |
| AI-slop (objective markers) | 30 | FAIL |
| Mixed | 15 | mix of PASS / FAIL / cohesion advisory |

**VERIFIED** (`labeled-latest.json`): recall **100%** (35/35) · FP **0%** · precision **100%** · cohesion advisory **5/5**.

## 2) Abandoned Airflow PRs → label → recreate under AIV

Sample: **61** closed-unmerged PRs (AIP-120 window), same code pushed through AIV.

### Labels applied

| Label | Meaning | Count (sample) |
|-------|---------|----------------|
| `abandoned_clean` | No markers, no AI disclosure, no AIV hard-fail | **42 (68.9%)** |
| `ai_disclosed` | Confirmed AI Yes / Generated-by in PR body | **16** |
| `ai_signal_advisory` | AIV cohesion/density warn only | **3** |
| `ai_slop_objective` | Conflict / SEARCH-REPLACE / elision / YOUR_CODE in patch | **0** |
| `ai_signal_hard` | AIV hard-fail without objective markers | **0** in label counts; **1** hard-fail after head+full rescore (#73124 stays `ai_disclosed`) |

### Catch rate when same PR runs through AIV (head + full config) — VERIFIED

| Metric | Value |
|--------|-------|
| Hard-fail on full sample | **1 / 61 (1.6%)** — [#73124](https://github.com/apache/airflow/pull/73124) |
| Hard-caught among `ai_disclosed` | **1 / 16 (6.2%)** |
| Hard ID among any AI-signal label | **1 / 19 (5.3%)** |
| Objective marker-style slop in sample | **0 / 61** |

**Conclusion for sharing:** Most abandoned PRs are **not** marker-style AI slop (**68.9%** `abandoned_clean`). Many disclose AI use without leaving objective artifacts — AIV correctly does **not** hard-block those. The gate identifies the rare artifact/sprawl cases (e.g. #73124).

Recreated on bench with labels in titles: PRs under `abandoned/<label>/airflow-pr-*` (see abandoned report for bench links).

## 3) Confirmed ChatGPT gold set (n=5 closed in window)

Closed-unmerged catch **1/2**; merged FP **0/3**. See `airflow-chatgpt-true-latest.json`.

## NOT CLAIMED

- Abandoned = AI authorship  
- Search `Generated-by` totals = AI-slop counts (template noise)  
- “We blocked X% of AIP-120’s 508 / saved Y% CI” as a measured bill  
- That AI disclosure alone should fail CI  

## Reproduce

```powershell
python benchmarks/airflow/scripts/generate-labeled-corpus.py
python benchmarks/airflow/scripts/run-labeled-benchmark.py
python benchmarks/airflow/scripts/benchmark-abandoned-labeled.py --limit 60 --inject --inject-clean 8
python benchmarks/airflow/scripts/rescore-ai-signal-head.py
# open benchmarks/airflow/reports/product-benchmark-latest.html
```
