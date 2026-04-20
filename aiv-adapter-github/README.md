# Module: `aiv-adapter-github`

**Role:** **CI report output** for GitHub-centric workflows. The default implementation writes the AIV report through **SLF4J** (stdout with typical Logback configuration), which GitHub Actions captures in logs. The repository’s **`.github/workflows/aiv.yml`** also pipes output to a file and may post a PR comment via `actions/github-script`.

## Scope

This module does **not** call the GitHub REST or Checks API directly; naming reflects the common deployment target. For Checks API or inline annotations, integrate separately or extend `ReportPublisher`.

## See also

- [`aiv-api`](../aiv-api/README.md) - `ReportPublisher`
- [`.github/README.md`](../.github/README.md) - workflows

**Author:** Vaquar Khan
