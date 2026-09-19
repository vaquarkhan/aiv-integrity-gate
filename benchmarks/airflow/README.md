# Airflow AIV benchmark

Labeled corpus and runner for measuring AIV precision/recall against **Apache Airflow** PRs, using the fork [`vaquarkhan/airflow`](https://github.com/vaquarkhan/airflow).

## What this is

| Piece | Purpose |
|-------|---------|
| `corpus/cases.json` | Indexed sample of **closed AI-slop-style** fixtures, **closed unmerged** real Airflow PRs, and **merged** real PRs |
| `fixtures/` | Tiny local git-friendly patches AIV can score without cloning all of Airflow |
| `.aiv/` | Airflow-oriented gate config (hard = syntax/invariant/design markers; soft density = **warn** → PR label) |
| `workflows/aiv.yml` | Drop-in GitHub Actions workflow for the Airflow fork |
| `scripts/run-benchmark.*` | Score fixtures locally and write a JSON summary under `results/` |

This is a **starting corpus**, not a catch-rate claim. Expand `cases.json` as you label more PRs.

## Labels (expected)

| `expected` | Meaning |
|------------|---------|
| `ai_slop_positive` | Fixture or PR that should trip hard or advisory AI-slop signals |
| `closed_unmerged_real` | Real closed-unmerged Airflow PR (often low value / abandoned — not proven AI) |
| `merged_real` | Real merged PR — should **not** hard-fail on objective markers |

## Soft AI-slop → PR tag (no block)

When a gate uses `severity: warn` and still finds AI-slop signals, AIV can **label** the PR (`aiv:ai-slop` by default) instead of failing CI:

```bash
java -jar aiv-cli.jar --diff origin/main --label-pr-on-advisory
```

Or in Actions (composite):

```yaml
- uses: vaquarkhan/aiv-integrity-gate@v1
  with:
    base-ref: origin/${{ github.base_ref }}
    label-pr-on-advisory: true
    advisory-pr-label: aiv:ai-slop
```

Requires `permissions: pull-requests: write` and a PR event (`AIV_GITHUB_PR_NUMBER` / `GITHUB_EVENT_PATH`).

## Run locally

From the **aiv-integrity-gate** repo root (after `mvn -pl aiv-cli -am package -DskipTests`):

```powershell
.\benchmarks\airflow\scripts\run-benchmark.ps1
```

```bash
./benchmarks/airflow/scripts/run-benchmark.sh
```

## Install AIV on the Airflow fork

1. Copy `benchmarks/airflow/.aiv/` → `vaquarkhan/airflow/.aiv/`
2. Copy `benchmarks/airflow/workflows/aiv.yml` → `vaquarkhan/airflow/.github/workflows/aiv.yml`
3. Push a branch on the fork and open a PR against `main` to exercise hard gates + advisory labeling

See also [docs/pipeline-aiv-copilot.md](../../docs/pipeline-aiv-copilot.md).
