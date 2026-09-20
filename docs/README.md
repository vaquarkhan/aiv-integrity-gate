# Documentation index

## Public (users)

| Document | Description |
|----------|-------------|
| **[PRE-COMMIT.md](PRE-COMMIT.md)** | Commit-time hook (recommended) |
| **[TUTORIAL.md](TUTORIAL.md)** | Step-by-step CLI and Actions |
| **[DEPLOYMENT.md](DEPLOYMENT.md)** | Enable AIV in a repo |
| **[DEVELOPER-CONFIGURATION.md](DEVELOPER-CONFIGURATION.md)** | `.aiv/config.yaml` and CLI flags |
| **[ARCHITECTURE.md](ARCHITECTURE.md)** | Modules and gates |
| **[DEVELOPER-GUIDE.md](DEVELOPER-GUIDE.md)** | Build and contribute |
| **[WHY-NOT-PMD-SEMGREP.md](WHY-NOT-PMD-SEMGREP.md)** | Diff-scoped gate vs whole-repo SAST |
| **[pipeline-aiv-copilot.md](pipeline-aiv-copilot.md)** | AIV hard gate, then advisory Copilot |
| **[MAVEN-VERSION.md](MAVEN-VERSION.md)** | Version and Central URL |
| **[PLUGIN-SECURITY.md](PLUGIN-SECURITY.md)** | Optional secrets gate |
| **[../benchmarks/true-positive/README.md](../benchmarks/true-positive/README.md)** | Objective true-positive fixtures |
| **[../benchmarks/high-breakage/README.md](../benchmarks/high-breakage/README.md)** | Demo corpus (expect FAIL) |
| **[../benchmarks/airflow/README.md](../benchmarks/airflow/README.md)** | Airflow labeled / abandoned harness |
| **[aiv-airflow-bench](https://github.com/vaquarkhan/aiv-airflow-bench)** | Public bench repo (injected PRs + Actions) |
| **[Bench GitHub Pages](https://vaquarkhan.github.io/aiv-airflow-bench/)** | Published metrics + raw JSON |

## Diagrams and demos

| Asset | Description |
|-------|-------------|
| [images/aiv-hero-banner.png](images/aiv-hero-banner.png) | README hero — what AIV is |
| [images/aiv-value-flow.png](images/aiv-value-flow.png) | PR diff → gates → pass/fail |
| [images/aiv-demo-fail.svg](images/aiv-demo-fail.svg) | Agent-paste FAIL findings demo |
| [images/aiv-demo-pass.svg](images/aiv-demo-pass.svg) | Sample PASS CLI report |
| [images/aiv-shift-left.svg](images/aiv-shift-left.svg) | Pre-commit → CI → merge |
| [images/aiv-hex-architecture.png](images/aiv-hex-architecture.png) | Module layout |

PNG alternates of the demos (`aiv-demo-fail.png`, `aiv-demo-pass.png`, `aiv-shift-left.png`) are also under [`images/`](images/) for non-SVG contexts.

## Also

| Resource | Description |
|----------|-------------|
| [dashboard/README.md](dashboard/README.md) | Optional HTML dashboard for JSON runs |
| [../example-project/](../example-project/) | Minimal sample layout |
| [https://github.com/vaquarkhan/aiv-airflow-bench](https://github.com/vaquarkhan/aiv-airflow-bench) | Live bench PRs / Actions |
| [https://vaquarkhan.github.io/aiv-airflow-bench/](https://vaquarkhan.github.io/aiv-airflow-bench/) | Pages report |

Maintainers: [benchmarks/airflow/internal/](../benchmarks/airflow/internal/). Roadmap: root [README](../README.md#roadmap).

**Author:** Vaquar Khan
