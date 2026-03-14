# AIV Developer Configuration Guide

How to configure AIV for your project. All config lives under `.aiv/` in the repo root.

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

1. Create `.aiv/config.yaml` (optional — defaults apply if missing)
2. Create `.aiv/design-rules.yaml` (optional — for design compliance)
3. Run AIV: `java -jar aiv-cli.jar --diff origin/main`

---

## File Layout

```
your-project/
├── .aiv/
│   ├── config.yaml          # Gate thresholds and enable/disable
│   └── design-rules.yaml    # Forbidden/required patterns
├── .github/
│   └── workflows/
│       └── ci.yml           # Add AIV job here
└── ...
```

---

## Adding AIV to an Existing Project (e.g. Apache Iceberg)

Step-by-step guide to enable AIV in any Git repo. No deployment — AIV is cloned and built by the workflow at runtime.

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
      entropy_threshold: 3.8
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

Create `.aiv/design-rules.yaml` with project-specific rules. A full Iceberg sample is at [samples/apache-iceberg/](samples/apache-iceberg/). Minimal example:

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
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17

      - name: Clone and build AIV
        run: |
          git clone https://github.com/vaquarkhan/aiv-integrity-gate.git aiv-src
          cd aiv-src
          mvn package -DskipTests -B -q -pl aiv-cli -am

      - name: Run AIV
        run: |
          java -jar aiv-src/aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar \
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
      ldr_threshold: 0.25      # Logic Density Ratio (0.0–1.0)
      entropy_threshold: 3.8   # Shannon entropy minimum

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
| `density`    | Logic density + entropy    | `ldr_threshold`, `entropy_threshold`, `refactor_net_loc_threshold`, `trusted_authors` | 0.25, 3.8, -50 |
| `design`     | Design compliance          | `rules_path`          | `.aiv/design-rules.yaml`    |
| `dependency` | Import vs lockfile         | `whitelist`           | —                           |
| `invariant`  | Invariant checks           | —                     | —                           |

### Density Gate

| Key                       | Type   | Description                                      | Default |
|---------------------------|--------|--------------------------------------------------|---------|
| `ldr_threshold`           | float  | Min Logic Density Ratio (control flow vs structure) | 0.25 |
| `entropy_threshold`       | float  | Min Shannon entropy (flags boilerplate)          | 3.8     |
| `refactor_net_loc_threshold` | int | Skip density when net LOC <= this (e.g. -50)     | -50     |
| `trusted_authors`         | list   | Author emails that bypass density check         | —       |
| `file_extensions`         | list   | Extensions to validate                          | All common |
| `languages`               | list   | Language names                                   | —       |

- **LDR < threshold** → fail (too much scaffolding, too little logic). LDR runs for Java only.
- **Entropy < threshold** → fail (repetitive/boilerplate code). Entropy runs for all configured extensions.
- **Net LOC <= refactor_net_loc_threshold** → skip (refactoring intent).
- **Author in trusted_authors** → skip.

### Dependency Gate

| Key         | Type   | Description                              | Default |
|-------------|--------|------------------------------------------|---------|
| `whitelist` | list   | Package names allowed without lockfile   | —       |

Validates Java imports against `pom.xml` and Python imports against `requirements.txt` or `pyproject.toml`. Fails on unknown imports. Use `whitelist` to allow packages that are not in the lockfile (for example, JDK or standard library modules).

### Design Gate

| Key               | Type   | Description                    | Default                  |
|-------------------|--------|--------------------------------|--------------------------|
| `rules_path`      | string | Path to design rules YAML      | `.aiv/design-rules.yaml` |
| `file_extensions` | list   | Extensions to validate         | All common source extensions |
| `languages`       | list   | Language names                 | —                        |

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

Add `/aiv skip` or `aiv skip` to any commit message in the PR. All gates are skipped.

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
- **forbidden_calls:** Substring match in file content (case-insensitive) → fail.
- **required_calls:** If constraint applies, file must contain all of these (case-insensitive) → fail if any missing.

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

### Examples

```bash
# Default (current dir, diff vs origin/main)
java -jar aiv-cli.jar

# Custom workspace and base ref
java -jar aiv-cli.jar --workspace /path/to/repo --diff origin/develop

# Compare two refs
java -jar aiv-cli.jar --diff origin/main --head feature-branch
```

---

## 4. CI Configuration

### GitHub Actions

```yaml
- uses: actions/checkout@v4
  with:
    fetch-depth: 0
- uses: apache/infrastructure-actions/aiv@main
  with:
    base-ref: origin/${{ github.base_ref }}
```

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
- `**/generated/**` — any path containing `generated/`
- `**/*.pb.java` — protobuf-generated Java files
- `**/vendor/**` — vendored dependencies

---

## 6. Defaults Summary

When `.aiv/config.yaml` is missing:

| Gate      | Enabled | Config                                      |
|-----------|---------|---------------------------------------------|
| density   | true    | ldr_threshold: 0.25, entropy_threshold: 3.8 |
| design    | true    | rules_path: .aiv/design-rules.yaml          |
| dependency| true    | whitelist: []                               |
| invariant | true    | (none)                                      |

When `.aiv/design-rules.yaml` is missing or empty, the design gate passes (no constraints).

---

## 7. Validation

Config is loaded at runtime. Invalid YAML or unknown keys are ignored; AIV falls back to defaults. Check logs if behavior is unexpected.

---

## See Also

- [TUTORIAL.md](TUTORIAL.md) — Step-by-step guide with examples and testing
- [EXAMPLE-WORKFLOW.md](EXAMPLE-WORKFLOW.md) — How AIV works with Git flow
