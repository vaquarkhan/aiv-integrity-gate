# AIV Developer Configuration Guide

You are in the right place if you need every knob AIV exposes: gates, YAML layout, CLI flags, and how defaults behave when files are missing. AIV works with any Git-based project (Java, Python, Go, Rust, and more). Project-specific settings live under `.aiv/` in the repo root.

**Author:** Vaquar Khan

---

## Pain Areas and Features

| Pain Area | Feature |
|-----------|---------|
| Reviewer overload, low-quality PR flood | Density gate |
| Design drift, wrong API usage | Design gate |
| Unknown imports, supply-chain risk | Dependency gate |
| Fragile edge-case code | Invariant gate |
| Urgent merges | `/aiv skip` |
| Legitimate refactors flagged | Refactor exception |
| Core maintainer friction | Trusted authors |

See [README.md](../README.md#problems-and-solutions) for the full mapping.

---

## Quick Start

1. Create `.aiv/config.yaml` (optional - defaults apply if missing)
2. Create `.aiv/design-rules.yaml` (optional - for design compliance)
3. Create `.aiv/doc-rules.yaml` (optional - for documentation validation)
4. Run AIV: `java -jar aiv-cli.jar --diff origin/main` (add `--include-doc-checks` when you want doc integrity without editing YAML)

For a full walkthrough (install, CI, troubleshooting), see **[TUTORIAL.md](TUTORIAL.md)**.

If you are editing **this** AIV repository, run `mvn clean verify` before opening a PR so tests and the **100%** JaCoCo line gate stay green.

---

## File Layout

```
your-project/
├── .aiv/
│   ├── config.yaml          # Gate thresholds and enable/disable
│   ├── design-rules.yaml    # Forbidden/required patterns
│   └── doc-rules.yaml       # Doc integrity (optional)
├── .github/
│   └── workflows/
│       └── aiv.yml          # AIV on PR/push (example: see upstream aiv-integrity-gate)
└── ...
```

### Global keys (top level of `config.yaml`)

Alongside `gates`, you may set:

| Key | Type | Description |
|-----|------|-------------|
| `exclude_paths` | list of strings | Repository-relative globs (`**`, `*`, optional `glob:` prefix). See `PathFilter` in source: invalid globs fall back to simple matching. **Negation (`!pattern`) is not supported**—only positive excludes. |
| `fail_fast` | boolean | If `true`, stop after the first failing gate. Default `false` runs all gates and aggregates failures. |
| `skip_allowlist` | list of emails | If non-empty, only these **git author emails** may honor `/aiv skip` on the latest commit (case-insensitive). This is metadata from `git log`, not cryptographic proof of identity. |

Example:

```yaml
exclude_paths:
  - "**/generated/**"

fail_fast: false

skip_allowlist:
  - "release@example.com"

gates:
  - id: density
    enabled: true
```

---

## Adding AIV to an Existing Project

Step-by-step guide to enable AIV in any Git repo. The commands below clone Apache Iceberg only as a stand-in; use your own remote if you are not working on Iceberg. No deployment - AIV is cloned and built by the workflow at runtime.

### Step 1: Clone the target repo

```bash
git clone https://github.com/apache/iceberg.git
cd iceberg
```

Or fork on GitHub and clone your fork.

### Step 2: Create the `.aiv` folder

```bash
mkdir .aiv
```

### Step 3: Create `.aiv/config.yaml`

Create `.aiv/config.yaml` with:

```yaml
gates:
  - id: density
    enabled: true
    config:
      ldr_threshold: 0.25
      entropy_threshold: 5.0
  - id: design
    enabled: true
    config:
      rules_path: .aiv/design-rules.yaml
  - id: dependency
    enabled: true
  - id: invariant
    enabled: true
```

### Step 4: Create `.aiv/design-rules.yaml`

Create `.aiv/design-rules.yaml` with project-specific rules. Minimal example:

```yaml
constraints:
  - id: snapshot-expiration
    keywords: [ExpireSnapshots, expireSnapshots]
    forbidden_calls: [table.removeSnapshots]
    required_calls: [ExpireSnapshots]
```

### Step 5: Create `.github/workflows/aiv.yml`

Create `.github/workflows/` if it does not exist, then create `aiv.yml`:

```yaml
name: AIV Gate
on:
  pull_request:
    branches: [main, master]

jobs:
  aiv:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5
        with:
          fetch-depth: 0

      - uses: actions/setup-java@c1e323688fd81a25caa38c78aa6df2d33d3e20d9
        with:
          distribution: temurin
          java-version: 17

      - name: Clone and build AIV
        run: |
          git clone https://github.com/vaquarkhan/aiv-integrity-gate.git aiv-src
          cd aiv-src
          mvn -B -ntp clean verify -pl aiv-cli -am

      - name: Run AIV
        run: |
          VERSION=$(cd aiv-src && mvn -q -DforceStdout -Dexpression=project.version help:evaluate)
          java -jar aiv-src/aiv-cli/target/aiv-cli-${VERSION}.jar \
            --workspace ${{ github.workspace }} \
            --diff origin/${{ github.base_ref }}
```

**Note:** If your main branch is not `main` or `master`, change the `branches` line (e.g. `branches: [develop]`).

### Step 6: Commit and push

```bash
git add .aiv .github
git commit -m "Add AIV gate for code validation"
git push origin main
```

### Step 7: Verify

1. Open a pull request to your fork (or upstream if you have access).
2. The **AIV Gate** workflow runs automatically.
3. Check the **Actions** tab for the run. Green = pass, red = fail.

### Summary

| Step | Action |
|------|--------|
| 1 | Clone the target repo |
| 2 | `mkdir .aiv` |
| 3 | Create `.aiv/config.yaml` |
| 4 | Create `.aiv/design-rules.yaml` |
| 5 | Create `.github/workflows/aiv.yml` |
| 6 | `git add`, `commit`, `push` |
| 7 | Open a PR and verify AIV runs |

---

## 1. config.yaml

**Path:** `.aiv/config.yaml`

Controls which gates run and their thresholds. If the file is missing, AIV uses built-in defaults. Malformed YAML throws an error instead of silently falling back.

### Full Example

```yaml
# .aiv/config.yaml

gates:
  - id: density
    enabled: true
    config:
      ldr_threshold: 0.25      # Logic Density Ratio (0.0-1.0)
      entropy_threshold: 5.0   # Shannon entropy minimum

  - id: design
    enabled: true
    config:
      rules_path: .aiv/design-rules.yaml

  - id: dependency
    enabled: true
    config:
      whitelist: []   # optional: allow packages not in lockfile

  - id: invariant
    enabled: true
    config: {}
```

### Gate Reference

| Gate ID      | Purpose                    | Config Keys           | Defaults                    |
|--------------|----------------------------|-----------------------|-----------------------------|
| `density`    | Logic density + entropy    | `ldr_threshold`, `entropy_threshold`, `refactor_net_loc_threshold`, `trusted_authors` | 0.25, 4.0, -50 |
| `design`     | Design compliance          | `rules_path`          | `.aiv/design-rules.yaml`    |
| `dependency` | Import vs lockfile         | `whitelist`           | -                           |
| `invariant`  | Invariant checks           | -                     | -                           |
| `doc-integrity` | Documentation validation | `rules_path`, `auto` | `.aiv/doc-rules.yaml`       |

For every changed documentation file, **`doc-integrity`** also checks **relative Markdown links** `[label](path)` (skipping `http://`, `https://`, `mailto:`): the path must resolve to a file in the workspace. If the link includes a **`#fragment`** and the target ends with `.md`, a matching **ATX heading slug** (GitHub-style) must exist in that file. Optional rules in `doc-rules.yaml` add required mentions and canonical command checks.

### Density Gate

| Key                       | Type   | Description                                      | Default |
|---------------------------|--------|--------------------------------------------------|---------|
| `ldr_threshold`           | float  | Min Logic Density Ratio (control flow vs structure) | 0.25 |
| `entropy_threshold`       | float  | Minimum normalized Shannon entropy for scanned files (below threshold fails the gate) | 4.0     |
| `refactor_net_loc_threshold` | int | Skip density when net LOC <= this (e.g. -50)     | -50     |
| `trusted_authors`         | list or string | Git **author emails** that bypass density when the head commit is signed (same caveats as `skip_allowlist`) | - |
| `file_extensions`         | list   | Extensions to validate                          | All common |
| `languages`               | list   | Language names                                   | -       |

- **LDR < threshold** → fail (too much scaffolding, too little logic). LDR runs for Java only.
- **Entropy < effective threshold** → fail (statistical low-signal heuristic; tune `entropy_threshold` / `entropy_min_bytes`). Entropy runs for all configured extensions.
- **Net LOC <= refactor_net_loc_threshold** → skip (refactoring intent).
- **Author in trusted_authors + signed head commit** → skip.

### Dependency Gate

| Key         | Type   | Description                              | Default |
|-------------|--------|------------------------------------------|---------|
| `whitelist` | list   | Package names allowed without lockfile   | -       |

Validates Java imports against `pom.xml` and Python imports against `requirements.txt` or `pyproject.toml`. Fails on unknown imports. Use `whitelist` to allow packages that are not in the lockfile (for example, JDK or standard library modules).

### Doc Integrity Gate

**Two ways to turn it on (pick one primary model to avoid surprises):**

| Mechanism | Effect |
|-----------|--------|
| CLI **`--include-doc-checks`** | Wraps config to turn **`doc-integrity` on** for that run and forces **`auto: false`**, so doc checks run **even when the diff has no doc files** (CI “always validate docs” without editing `config.yaml`). |
| Config **`doc-integrity` → `enabled: true`** | Gate is on whenever AIV runs; set **`auto: true`** so the orchestrator **only** runs it when the diff touches doc-like paths (typical for code-heavy repos). |

With **`auto: true`** in `config.yaml` alone, the gate is skipped on code-only PRs. **`--include-doc-checks`** is the opposite default for `auto`: it sets **`auto: false`** so every run evaluates documentation rules.

Validates markdown and text files (.md, .txt, .rst, AGENTS.md, CLAUDE.md, CONTRIBUTING.md) for: path existence, cross-reference validity, required mentions (YAML-configurable), command completeness, and path fabrication.

| Key         | Type    | Description                                    | Default                |
|-------------|---------|------------------------------------------------|------------------------|
| `rules_path`| string  | Path to doc rules YAML                         | `.aiv/doc-rules.yaml`  |
| `auto`      | boolean | When true, run only when diff has doc files    | false                  |

### Design Gate

| Key               | Type   | Description                    | Default                  |
|-------------------|--------|--------------------------------|--------------------------|
| `rules_path`      | string | Path to design rules YAML      | `.aiv/design-rules.yaml` |
| `file_extensions` | list   | Extensions to validate         | All common source extensions |
| `languages`       | list   | Language names                 | -                        |

### Multi-Language Support

All gates support multiple languages via `file_extensions` or `languages`:

```yaml
gates:
  - id: design
    config:
      rules_path: .aiv/design-rules.yaml
      file_extensions: [".java", ".py", ".go", ".rs", ".kt"]
  - id: density
    config:
      languages: [java, python, go]
```

Default extensions: `.java`, `.kt`, `.py`, `.go`, `.rs`, `.scala`, `.js`, `.ts`, `.jsx`, `.tsx`, `.c`, `.cpp`, `.h`, `.rb`, `.sh`, `.bash`

Density LDR check runs for Java only; entropy runs for all configured extensions.

### Human Override

Put `/aiv skip` or `aiv skip` on its **own line** in the **latest** commit message on the PR head (anchored pattern - not a substring in unrelated prose). All gates are skipped for that run. If **`skip_allowlist`** is configured, the latest commit’s author email must match an entry.

### Disabling Gates

```yaml
gates:
  - id: density
    enabled: true
  - id: design
    enabled: false   # Skip design compliance
  - id: invariant
    enabled: false
```

---

## 2. design-rules.yaml

**Path:** `.aiv/design-rules.yaml` (or path set in `config.yaml`)

Defines constraints: forbidden calls and required calls per area of the codebase.

### Schema

```yaml
constraints:
  - id: <constraint-name>
    keywords: [<strings>]        # Optional: when to apply
    forbidden_calls: [<strings>]
    required_calls: [<strings>]
```

### Example: Snapshot Expiration

```yaml
constraints:
  - id: snapshot-expiration
    keywords: [ExpireSnapshots, expireSnapshots]
    forbidden_calls: [table.removeSnapshots]
    required_calls: [ExpireSnapshots]
```

- **keywords:** Constraint applies only if file content or path contains any keyword. Empty = applies to all matched files. Matching is case-insensitive.
- **forbidden_calls:** Token-aware, case-insensitive match in file content → fail.
- **required_calls:** If constraint applies, file must contain all of these token-aware, case-insensitive patterns → fail if any missing.

### Example: No Java Serialization

```yaml
constraints:
  - id: no-serialization
    keywords: []
    forbidden_calls:
      - implements Serializable
      - java.io.ObjectOutputStream
    required_calls: []
```

### Example: API Usage

```yaml
constraints:
  - id: use-new-api
    keywords: [DataFrame, Dataset]
    forbidden_calls: [oldDeprecatedMethod]
    required_calls: [newRecommendedMethod]
```

---

## 3. CLI Arguments

| Argument      | Description                    | Default        |
|---------------|--------------------------------|----------------|
| `--workspace` | Path to repo root              | `.`            |
| `--diff`      | Base ref for diff              | `origin/main`  |
| `--head`      | Head ref for diff              | `HEAD`         |
| `--include-doc-checks` | For this run, wrap config so the **doc-integrity** gate is enabled for documentation files (same effect as turning it on in YAML for local experiments). | (flag absent) |
| `--doctor`    | Informational run: same checks, exit `0` (tune before enforcement). JSON includes **`doctor_mode: true`**; SARIF includes **`runs[].properties.doctorMode: true`**. | (flag absent) |
| `--output-json` *path* | Write a JSON report (`schema_version: 2`, top-level **`doctor_mode`**, per-gate **`findings`**) after the run. Treat **`passed`** as non-blocking when **`doctor_mode`** is `true`. | (no file) |
| `--output-sarif` *path* | Write SARIF 2.1.0 after the run; run **`properties.doctorMode`** mirrors JSON `doctor_mode`. | (no file) |
| `--quiet` | Suppress human stdout report and INFO line (use with `--output-json` / `--output-sarif` for machine-only output). | (flag absent) |
| `--publish-github-checks` | After a successful run, POST a GitHub Check run with annotations (needs `GITHUB_TOKEN`, `GITHUB_REPOSITORY`, `GITHUB_SHA` or `AIV_GITHUB_HEAD_SHA`). | (flag absent) |
| `--warnings-exit-code` *n* | If the run **passed** but there were **notices** (e.g. skipped oversized files), exit with code *n* (e.g. `4`) instead of `0`. | `0` (disabled) |
| `--version`, `-V` | Print CLI version and exit   | -              |

**Exit codes:** `0` = success; `1` = blocking gate failed; `2` = bad args/config; `3` = git failure. Optional non-zero exit when passes-with-notices if `--warnings-exit-code` is set.

### Examples

```bash
# Default (current dir, diff vs origin/main)
java -jar aiv-cli.jar

# Custom workspace and base ref
java -jar aiv-cli.jar --workspace /path/to/repo --diff origin/develop

# Compare two refs
java -jar aiv-cli.jar --diff origin/main --head feature-branch

# Also run doc integrity checks for this invocation
java -jar aiv-cli.jar --workspace /path/to/repo --diff origin/main --include-doc-checks

# Machine-readable report for dashboards
java -jar aiv-cli.jar --diff origin/main --output-json target/aiv-report.json
```

---

## 4. CI Configuration

### GitHub Actions

**Recommended (composite action - downloads shaded JAR from Maven Central):**

```yaml
- uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5
  with:
    fetch-depth: 0
- uses: vaquarkhan/aiv-integrity-gate@v1
  with:
    base-ref: origin/${{ github.base_ref }}
    aiv-version: "1.0.4"
```

See the repository root **`action.yml`** for optional inputs (`cli-jar-url`, `maven-central-base`, etc.).

### Jenkins

```groovy
stage('AIV') {
  steps {
    sh 'java -jar aiv-cli.jar --workspace ${WORKSPACE} --diff origin/${CHANGE_TARGET}'
  }
}
```

### Environment

No env vars required for default (free) mode. Config is read from `.aiv/` in the workspace.

For **`--publish-github-checks`:** set `GITHUB_TOKEN`, `GITHUB_REPOSITORY` (`owner/repo`), and `GITHUB_SHA` (or `AIV_GITHUB_HEAD_SHA` for PR head). Optional: `AIV_GITHUB_CHECKS_URL` to override the Checks API URL (advanced).

---

## 5. Tuning for Your Project

### Too Many False Positives (Density)

- Lower `ldr_threshold` (e.g. 0.2) or lower `entropy_threshold` (e.g. 3.5).
- Or disable the density gate: `enabled: false`.

### Design Rules Too Strict

- Add more specific `keywords` so constraints apply only where relevant.
- Remove or relax `required_calls` if not always applicable.

### Design Rules Too Loose

- Add more `forbidden_calls` for known bad patterns.
- Use empty `keywords` to apply constraints to all Java files.

### Path Exclusions

Skip generated code, vendored dependencies, or other paths:

```yaml
# .aiv/config.yaml
exclude_paths:
  - "**/generated/**"
  - "**/target/**"
  - "**/*.pb.java"
  - "**/vendor/**"

gates:
  - id: density
    enabled: true
  # ...
```

Glob patterns use Java PathMatcher syntax. Common patterns:
- `**/generated/**` - any path containing `generated/`
- `**/*.pb.java` - protobuf-generated Java files
- `**/vendor/**` - vendored dependencies

---

## 6. Defaults Summary

When `.aiv/config.yaml` is missing, the CLI’s built-in default (see `YamlConfigProvider`) turns on density, design, dependency, and invariant, and leaves **doc-integrity** present but **disabled** unless you enable it in YAML or pass `--include-doc-checks`.

| Gate      | Enabled | Config                                      |
|-----------|---------|---------------------------------------------|
| density   | true    | ldr_threshold: 0.25, entropy_threshold: 5.0 |
| design    | true    | rules_path: .aiv/design-rules.yaml          |
| dependency| true    | whitelist: []                               |
| invariant | true    | (none)                                      |
| doc-integrity | false | rules_path: .aiv/doc-rules.yaml, auto: true |

When `.aiv/design-rules.yaml` is missing or empty, the design gate passes (no constraints).

---

## 7. Validation

Config is loaded at runtime from `.aiv/config.yaml`. If the file **exists** but is not valid YAML or cannot be parsed into the expected shape, loading throws an `IllegalArgumentException` with a clear message (you will see it in CI logs). That is intentional: you get a hard failure instead of silent guessing.

If the config file is **missing**, AIV uses the defaults in the table above. When something looks wrong, confirm the file path is `workspace/.aiv/config.yaml` and run a quick syntax check with any YAML linter.

---

## See Also

- [README.md](../README.md) - Overview and quick start
- [TUTORIAL.md](TUTORIAL.md) - Detailed getting-started guide
- [DEPLOYMENT.md](DEPLOYMENT.md) - CI, workflows, Maven Central / Marketplace
