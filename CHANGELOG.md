# Changelog

All notable changes to **AIV Integrity Gate** are documented here. Version numbers follow the Maven reactor (`aiv-gate` parent POM).

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
