# Module: `aiv-adapter-github`

**Role:** GitHub-oriented report publishers used by the CLI.

| Class | Purpose |
|-------|---------|
| `StdoutReportPublisher` | Human-readable report via SLF4J / stdout |
| `GithubChecksPublisher` | POST a Check run with annotations (`--publish-github-checks`) |
| `GithubPrLabelPublisher` | Add/remove a PR label for advisory AI-slop findings (`--label-pr-on-advisory`) |

## Advisory PR labeling

When a gate is configured with `severity: warn`, failures do not block CI. With `--label-pr-on-advisory`, AIV still tags the PR (default label `aiv:ai-slop`) so reviewers see soft AI-slop signals. On a clean run, the label is removed.

Requires `GITHUB_TOKEN`, `GITHUB_REPOSITORY`, and `AIV_GITHUB_PR_NUMBER` or a `pull_request` `GITHUB_EVENT_PATH`. Optional: `AIV_GITHUB_API_BASE` / JVM property `aiv.github.labels.api.base` for tests.

Config (`.aiv/config.yaml`):

```yaml
advisory_pr_label: aiv:ai-slop
advisory_label_gates:
  - design
  - invariant
  - density
```

## See also

- [`aiv-api`](../aiv-api/README.md) - `ReportPublisher`, `AIVConfig`
- [`.github/README.md`](../.github/README.md) - workflows
- [`benchmarks/airflow/README.md`](../benchmarks/airflow/README.md) - Airflow corpus + fork workflow

**Author:** Vaquar Khan
