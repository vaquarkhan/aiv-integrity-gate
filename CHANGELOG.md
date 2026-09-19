# Changelog

All notable changes to **AIV Integrity Gate** are documented here. Version numbers follow the Maven reactor (`aiv-gate` parent POM).

## Unreleased

### User-facing

- **Advisory PR label:** When gates use `severity: warn` (do not block CI) but still find AI-slop signals, `--label-pr-on-advisory` applies a GitHub PR label (default `aiv:ai-slop`) and removes it on clean runs. Config: `advisory_pr_label`, `advisory_label_gates`. Composite action inputs: `label-pr-on-advisory`, `advisory-pr-label`.
- **Airflow benchmark:** [`benchmarks/airflow/`](benchmarks/airflow/) — labeled corpus (`corpus/cases.json`), synthetic AI-slop + clean fixtures, fork workflow for [`vaquarkhan/airflow`](https://github.com/vaquarkhan/airflow), and `scripts/run-benchmark.*`.
- **Syntax gate (`aiv-plugin-syntax`):** parse-validity pre-gate for changed Java, Python, YAML, and JSON. Precision-first skips for missing Python toolchain, Helm/Jinja templates, `tsconfig*.json` (JSONC), and `.java` files that are actually Dockerfiles (`#` first line). Rule id: `syntax.parse`. `aiv explain syntax`.
- **Dependency gate:** Python **stdlib** allowlist and **first-party** package scan (top-level dirs with `__init__.py`) so legitimate Airflow-style imports are not false-flagged.
- **Invariant gate:** **AI edit-artifact** rule on code files only (`invariant.ai-edit-artifact`: elision markers, assistant chatter, SEARCH/REPLACE blocks), in addition to merge-conflict and TBD/FIXME/XXX checks.
- **Two-stage CI:** [`.github/workflows/copilot-review.yml`](.github/workflows/copilot-review.yml) requests advisory Copilot review only after **AIV Gate** succeeds; [`.github/copilot-instructions.md`](.github/copilot-instructions.md) steers generation. See [docs/pipeline-aiv-copilot.md](docs/pipeline-aiv-copilot.md).
- **Docs:** [ARCHITECTURE.md](docs/ARCHITECTURE.md), [DEVELOPER-GUIDE.md](docs/DEVELOPER-GUIDE.md), deepened [TUTORIAL.md](docs/TUTORIAL.md), value-flow and hex-architecture diagrams under `docs/images/`.

### Maintainers

- Reactor module `aiv-plugin-syntax` registered in root and `aiv-cli` POMs (ServiceLoader discovery in the shaded JAR).

## 1.0.4 - 2026-04-19

### Breaking changes

- **Maven artifact rename:** `io.github.vaquarkhan:aiv-plugin-design-lucene` is now **`io.github.vaquarkhan:aiv-plugin-design`** (same Java packages). Update any explicit dependency or BOM entry that referenced the old artifact id.
- **`--output-json` consumers:** JSON reports moved from **`schema_version: 1`** (1.0.3) to **`schema_version: 2`** in 1.0.4. Tooling that assumed the v1 shape must be updated; see this changelog’s 1.0.4 structured JSON bullet for the new fields.

### User-facing

- **SARIF:** `--output-sarif <path>` writes SARIF 2.1.0 for GitHub Code Scanning and compatible viewers.
- **Structured JSON:** `--output-json` reports use **`schema_version: 2`**. Top-level **`doctor_mode`** (`true`/`false`) flags informational **`doctor`** runs so CI can ignore `passed` when tuning. Each gate includes a **`findings`** array with `rule_id`, `file`, `start_line`, optional range columns, and `message` (for SARIF, dashboards, and tooling).
- **SARIF run properties:** Each run includes **`properties.doctorMode`** (boolean) for the same distinction.
- **GitHub Checks:** `--publish-github-checks` creates a Check run with inline annotations from findings. Requires `GITHUB_TOKEN`, `GITHUB_REPOSITORY` (`owner/repo`), and `GITHUB_SHA` or `AIV_GITHUB_HEAD_SHA`. Optional: `AIV_GITHUB_CHECKS_URL` or JVM property `aiv.github.checks.url` to override the Checks API URL (e.g. testing).
- **Composite action:** [action.yml](action.yml) default `aiv-version` is **1.0.4**.

### Maintainers

- Full reactor and modules version **1.0.4**; documentation and install script examples updated to match.

## 1.0.3 - 2026-04-19

### User-facing

- **Positioning:** README leads with a single product sentence; Apache License 2.0 is clarified as the *license*, not an Apache Software Foundation incubation project.
- **Why not PMD / Semgrep:** See [docs/WHY-NOT-PMD-SEMGREP.md](docs/WHY-NOT-PMD-SEMGREP.md) and the comparison table at the top of the README.
- **Install without building:** [scripts/install-aiv.sh](scripts/install-aiv.sh) downloads the shaded CLI from Maven Central.
- **Version alignment:** [docs/MAVEN-VERSION.md](docs/MAVEN-VERSION.md) documents `${project.version}`, Central coordinates, and `mvn help:evaluate`; [scripts/print-maven-version.sh](scripts/print-maven-version.sh) prints the reactor root version for scripts.
- **Structured output:** `--output-json <path>` writes a versioned JSON report (`schema_version: 1`) for CI and dashboards.
- **Exit codes:** `0` = success, `1` = gate failure, `2` = invalid configuration or arguments, `3` = git subprocess failure. Optional `--warnings-exit-code N` (e.g. `4`) when the run **passed** but emitted **notices** (e.g. oversized files skipped) - for pipelines that need to branch on degraded runs.

### Maintainers

- Unit test coverage for `RecordingReportPublisher` (delegation and last result).
- Composite [action.yml](action.yml) default `aiv-version` updated to **1.0.3**.
- Example workflow prefers downloading the published JAR instead of cloning and running `mvn verify` for every PR check.

### Not in this release

- SARIF output, GitHub Checks annotations, baseline suppressions, and `aiv-plugin-security` remain on the roadmap (see README “Roadmap”).
