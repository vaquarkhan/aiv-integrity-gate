# Airflow AIV benchmark

End-to-end, third-party-validatable scoring of AIV against **real Apache Airflow PRs** plus synthetic fixtures.

| Artifact | Purpose |
|----------|---------|
| [METHODOLOGY.md](METHODOLOGY.md) | Labeling rules, discovery, FP/TP definitions |
| [AIP-120-AND-AIV-SHAREABLE.md](AIP-120-AND-AIV-SHAREABLE.md) | **Verified-only** brief for wider Airflow/AIP-120 discussion |
| [corpus/cases.json](corpus/cases.json) | Labeled cases (schema v2) with PR metadata + marker discovery |
| [scripts/run-e2e-benchmark.py](scripts/run-e2e-benchmark.py) | `discover` + `run` → JSON/HTML reports |
| [reports/latest.html](reports/latest.html) | Latest human report (after a run) |
| [fixtures/](fixtures/) | Synthetic positives/negatives for regression |
| [.aiv/](.aiv/) | Airflow-oriented gate config (density = warn → PR label) |
| [workflows/aiv.yml](workflows/aiv.yml) | Fork CI for [vaquarkhan/airflow](https://github.com/vaquarkhan/airflow) |

## Labels (ground truth)

| `label.class` | Meaning |
|---------------|---------|
| `objective_slop` / `synthetic_positive` | Verified objective markers → expect **hard fail** |
| `merged_control` / `synthetic_negative` | Expect **hard pass** (fail = **FP**) |
| `closed_unmerged_sample` | Closed unmerged **without** markers — population flag-rate only, **not** AI-slop proof |

## Latest published snapshot (2026-09-19)

| Metric | Value |
|--------|-------|
| Cases | 51 (4 synthetic + 35 closed-unmerged + 12 merged) |
| Objective markers in closed scan | **0** (markers are rare; not a volume filter alone) |
| Hard precision (synthetics + controls) | **100%** |
| Recall on labeled positives | **100%** (3/3 synthetics) |
| FP rate on merged controls | **0%** (0/12) |
| Closed-unmerged hard flag rate | **2.9%** (1/35) — [PR #73124](https://github.com/apache/airflow/pull/73124) hit `invariant.placeholder` (FIXME in a workflow file) |

Open [reports/latest.html](reports/latest.html).


Gates with `severity: warn` can label the PR (`aiv:ai-slop`) via `--label-pr-on-advisory` without failing CI.

## Run end-to-end

```powershell
# 1) Discover / refresh corpus from apache/airflow
python benchmarks/airflow/scripts/run-e2e-benchmark.py discover --closed-limit 35 --merged-limit 12

# 2) Score with local aiv-cli and write HTML
mvn -pl aiv-cli -am package -DskipTests
python benchmarks/airflow/scripts/run-e2e-benchmark.py run
```

Open `benchmarks/airflow/reports/latest.html`.

Legacy fixture-only runners: `scripts/run-benchmark.ps1` / `run-benchmark.sh`.

## Should we add more gates?

See the gate table in the HTML report and [METHODOLOGY.md](METHODOLOGY.md). Short answer: ship **added-lines-only** scoping next; keep LLM judges advisory; secrets as optional roadmap — not a substitute for precision hard gates.
