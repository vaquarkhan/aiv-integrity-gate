# AIV Gate - Automated Integrity Validation

Apache Infrastructure Action for running AIV (Automated Integrity Validation) on pull requests. Catches logic density issues, design violations, and low-quality code patterns.

**Author:** Vaquar Khan

## 3-Line Opt-In

Projects opt-in by adding one of the following to their `.github/workflows`:

### Option 1: Reusable Workflow (3 lines)

Add to your existing workflow or create `.github/workflows/aiv.yml`:

```yaml
jobs:
  aiv:
    uses: apache/infrastructure-actions/.github/workflows/aiv.yml@main
```

### Option 2: Composite Action (4 lines)

```yaml
- uses: actions/checkout@v4
  with:
    fetch-depth: 0
- uses: apache/infrastructure-actions/aiv@main
  with:
    base-ref: origin/${{ github.base_ref }}
```

### Option 3: .asf.yaml (future)

If ASF adds workflow injection to `.asf.yaml`, projects could opt-in with:

```yaml
github:
  workflows:
    aiv: true
```

*(.asf.yaml does not support workflow injection yet. Use Option 1 or 2.)*

## Inputs

| Input | Required | Default | Description |
|-------|----------|---------|-------------|
| `base-ref` | Yes | — | Base ref for diff, e.g. `origin/main` or `origin/${{ github.base_ref }}` |
| `workspace` | No | `${{ github.workspace }}` | Path to workspace |

## Usage in pull_request workflow

```yaml
name: CI
on:
  pull_request:
    branches: [main, master]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: mvn test

  aiv:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - uses: apache/infrastructure-actions/aiv@main
        with:
          base-ref: origin/${{ github.base_ref }}
```

## Requirements

- AIV CLI must be published to Maven Central (`org.apache.aiv:aiv-cli`) or GitHub Releases
- No secrets required (default mode uses Lucene + YAML rules, no paid APIs)
- Works with fork PRs (no `pull_request_target` needed)

## Output

- Exit code 0 = pass, 1 = fail
- Report printed to stdout
- CI fails the job on non-zero exit

## Config

Projects can add `.aiv/config.yaml` and `.aiv/design-rules.yaml` to customize thresholds and design constraints. See the [AIV documentation](https://github.com/apache/aiv-gate) for details.
