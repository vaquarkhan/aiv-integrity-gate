# Old vs new AIV on the public aiv-airflow-bench corpus

This folder validates the v2 changes against the test data published in
[vaquarkhan/aiv-airflow-bench](https://github.com/vaquarkhan/aiv-airflow-bench): 89 branches
(`labeled/*` synthetic fixtures, `case/*`, `abandoned/<label>/*` recreations of real closed-unmerged
Apache Airflow PRs, `snapshot/*`).

## What is compared

| | old | new |
|--|--|--|
| build | commit `bd9a99d` (1.0.4 line, before v2), built in a git worktree | v2 working tree |
| base ref | `main` (three-dot merge-base diff) | same |
| config | bench `.aiv/config.yaml` from the head checkout | same file, read from the base ref |
| gates | syntax, design, cohesion(warn), invariant, density(warn) | same, plus test-adequacy, duplication(warn), package-existence, security (defaults) |

## Run it

```powershell
git clone https://github.com/vaquarkhan/aiv-airflow-bench ..\aiv-airflow-bench
git -C ..\aiv-airflow-bench fetch origin "+refs/heads/*:refs/remotes/origin/*"
python benchmarks\airflow-bench-compare\run_compare.py --bench ..\aiv-airflow-bench --old <old-jar> --new aiv-cli\target\aiv-cli-1.0.4.jar --out benchmarks\airflow-bench-compare\results
python benchmarks\airflow-bench-compare\aggregate.py benchmarks\airflow-bench-compare\results ..\aiv-airflow-bench
python benchmarks\airflow-bench-compare\show_findings.py            # hard findings for manual FP review
```

Outputs: `results/COMPARE.md` (tables and per-branch rows), `results/summary.json`, `results/compare.csv`,
`results/reports/<branch>.{old,new}.json` (full AIV JSON reports).

## Result (2026-10-17 run)

| cohort | n | old hard | new hard | genuine hard (excl. syntax + fragment artifacts) old / new | advisory evidence old / new |
|---|---|---|---|---|---|
| labeled + case (ground truth) | 54 | 38/38 TP, 0 FP | 38/38 TP, 0 FP | same | new adds `missing-tests` advisory on fixtures that ship no tests |
| abandoned ai_disclosed | 16 | 13 | 14 | 0 / 0 | 0 / 3 |
| abandoned ai_signal_advisory | 3 | 2 | 2 | 0 / 0 | 1 / 2 |
| abandoned_clean | 8 | 5 | 5 | 0 / 0 | 0 / 1 |
| snapshot | 7 | 5 | 5 | 0 / 0 | 0 / 0 |

Three things this run taught us, all written into the code or the tables:

1. **The corpus's real-PR branches are hunk fragments, not files.** The syntax gate fails them for both
   builds (`expected '<document start>'` on a YAML that begins mid-list). Those verdicts are a corpus
   artifact; `aggregate.py` reports hard-fails with and without syntax.
2. **Two real false positives in the new sensors were caught and fixed here** before the final numbers:
   ruff rule codes (`"COM812"`) inside `[tool.ruff.lint]` were read as PyPI packages (apache/airflow#73056);
   a multi-line `@pytest.mark.parametrize` signature whose `):` sits at the def's indentation ended the test
   body early (apache/airflow#73124). Both have regression tests.
3. **Remaining assertion-free hits on real PRs are tests whose body is cut off at the fragment's EOF.**
   `aggregate.py` detects that (function never closes before EOF) and lists them as artifacts.

Net: on this corpus neither build hard-blocks a real abandoned PR once artifacts are removed, which is
the correct behavior for disclosed-AI PRs that have real tests and real dependencies. What v2 adds here
is reviewer evidence (`missing-tests` on 8 PRs) and the closed bypass paths; the new hard sensors
(hallucinated packages, assertion-free tests) are not exercised by this corpus and are validated by unit
fixtures. A catch rate for those needs agent patches with hidden-test ground truth.

## Second pass: the v2 `airflow` preset on the same corpus

`run_compare.py` judges every branch with the bench repository's own `.aiv/config.yaml`, which enables only
the 1.0.4-era gates. `run_v2_config.py` asks a different question: what does v2 report when a project adopts
the shipped `airflow` preset (`rules` with the airflow pack, ruff AIR through `external`, test-adequacy,
package-existence, security, pr-metadata, cohesion, duplication; everything advisory)? The preset is written
into the working tree and read with `--config-from-workspace`, so this is a measurement, not a gate run.

```powershell
python benchmarks/airflow-bench-compare/run_v2_config.py --bench ../aiv-airflow-bench `
    --jar aiv-cli/target/aiv-cli-1.0.4.jar --preset aiv-cli/src/main/resources/presets/airflow/config.yaml `
    --out benchmarks/airflow-bench-compare/results-v2config
```

Output: `results-v2config/SUMMARY.md`, `summary.json`, `results.json`, per-branch reports.

What the first run of this pass taught us (2026-10-17):

1. **It found a product defect.** Every branch exited 2. A config that sets `ai_slop_pr_label` or
   `ai_slop_pr_comment` (the presets do) made the CLI attempt GitHub publication on a local run and turn the
   missing `GITHUB_TOKEN` into exit 2, so any pre-commit run with a preset would have failed. Fixed in
   `Main`: config-requested publication is skipped with a notice outside a GitHub context; explicit flags and
   `AIV_*` environment requests still fail loudly. Regression test in `MainTest`.
2. **`rules.airflow.*` fired zero times.** The corpus is Airflow core and provider PRs (tests, CLI, UI,
   configuration), not DAG files, so a DAG-definition rule pack has nothing to say about it. That is the
   expected result, and it is why the pack is validated by unit fixtures and the e2e script, not by this
   corpus. A real measurement needs a DAG repository.
3. **`external.ruff-airflow.invalid-syntax` is the same fragment artifact as the syntax gate**, now coming
   from ruff (hundreds of findings because every truncated file is a syntax error to ruff). On whole files
   it would not fire. The two `external.ruff-airflow.no-output` findings are ruff producing no SARIF for a
   fragment set; the gate reports that instead of passing silently, which is the intended behaviour.
4. **`test-adequacy.missing-tests` fires on 45 of 50 labeled fixtures** because the synthetic fixtures ship
   no tests by design. On a real repository the rule is scoped by conventional test discovery; on this
   corpus it is noise, and it stays advisory for that reason.
