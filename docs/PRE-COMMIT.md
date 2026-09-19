# Pre-commit (shift-left)

Highest-ROI install: catch objectively broken AI/agent output **before** the PR exists.

<p align="center">
  <img src="images/aiv-shift-left.svg" alt="AIV at pre-commit, then GitHub Action, then merge only if hard gates pass" width="880" />
</p>

## Requirements

- JDK 17+
- `git`
- `curl` (bash) or PowerShell (Windows) for Maven Central download on first run

## Option A - official hook repo

`.pre-commit-config.yaml`:

```yaml
repos:
  - repo: https://github.com/vaquarkhan/aiv-integrity-gate
    rev: v1.0.4   # pin a tag or commit
    hooks:
      - id: aiv-gate          # bash / Git Bash / macOS / Linux
      # - id: aiv-gate-windows  # native Windows PowerShell instead
```

Then:

```bash
pre-commit install
# stage files, then:
pre-commit run aiv-gate --all-files   # or commit as usual
```

The hook gates **staged** changes only (index tree vs `HEAD`).

## Option B - local script

```bash
# Linux / macOS / Git Bash
./scripts/aiv-pre-commit.sh

# Windows PowerShell
./scripts/aiv-pre-commit.ps1
```

Environment overrides:

| Variable | Meaning |
|----------|---------|
| `AIV_CLI_JAR` | Path to shaded `aiv-cli.jar` |
| `AIV_VERSION` | Maven Central version (default `1.0.4`) |
| `AIV_WORKSPACE` | Repo root |
| `AIV_DIFF_BASE` / `AIV_DIFF_HEAD` | Skip staged-tree mode; compare these refs |

## CI still matters

Keep the [composite action](../action.yml) as a backstop. Pre-commit is prevention; CI catches skipped hooks.

## Claims

This path fails objectively broken paste early without blocking good commits when hard rules stay precise.
