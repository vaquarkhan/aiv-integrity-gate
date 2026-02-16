# AIV Developer Configuration Guide

How to configure AIV for your project. All config lives under `.aiv/` in the repo root.

**Author:** Vaquar Khan

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
  - id: invariant
    enabled: true
```

### Step 4: Create `.aiv/design-rules.yaml`

Create `.aiv/design-rules.yaml` with project-specific rules. Example for Iceberg:

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

Controls which gates run and their thresholds. If the file is missing, AIV uses built-in defaults.

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

  - id: invariant
    enabled: true
    config: {}
```

### Gate Reference

| Gate ID    | Purpose                    | Config Keys           | Defaults                    |
|------------|----------------------------|-----------------------|-----------------------------|
| `density`  | Logic density + entropy    | `ldr_threshold`, `entropy_threshold` | 0.25, 3.8 |
| `design`   | Design compliance          | `rules_path`          | `.aiv/design-rules.yaml`    |
| `invariant`| Invariant checks           | —                     | —                           |

### Density Gate

| Key                 | Type   | Description                                      | Default |
|---------------------|--------|--------------------------------------------------|---------|
| `ldr_threshold`     | float  | Min Logic Density Ratio (control flow vs structure) | 0.25 |
| `entropy_threshold` | float  | Min Shannon entropy (flags boilerplate)          | 3.8     |

- **LDR < threshold** → fail (too much scaffolding, too little logic)
- **Entropy < threshold** → fail (repetitive/boilerplate code)

### Design Gate

| Key          | Type   | Description              | Default                  |
|--------------|--------|--------------------------|--------------------------|
| `rules_path` | string | Path to design rules YAML | `.aiv/design-rules.yaml` |

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

- **keywords:** Constraint applies only if file content or path contains any keyword. Empty = applies to all Java files.
- **forbidden_calls:** Substring match in file content → fail.
- **required_calls:** If constraint applies, file must contain all of these → fail if any missing.

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

- Raise `ldr_threshold` (e.g. 0.2) or lower `entropy_threshold` (e.g. 3.5).
- Or disable the density gate: `enabled: false`.

### Design Rules Too Strict

- Add more specific `keywords` so constraints apply only where relevant.
- Remove or relax `required_calls` if not always applicable.

### Design Rules Too Loose

- Add more `forbidden_calls` for known bad patterns.
- Use empty `keywords` to apply constraints to all Java files.

### Skip AIV for Certain Paths

AIV does not support path exclusions yet. Workaround: disable gates or adjust thresholds.

---

## 6. Defaults Summary

When `.aiv/config.yaml` is missing:

| Gate     | Enabled | Config                                      |
|----------|---------|---------------------------------------------|
| density  | true    | ldr_threshold: 0.25, entropy_threshold: 3.8 |
| design   | true    | rules_path: .aiv/design-rules.yaml           |
| invariant| true    | (none)                                      |

When `.aiv/design-rules.yaml` is missing or empty, the design gate passes (no constraints).

---

## 7. Validation

Config is loaded at runtime. Invalid YAML or unknown keys are ignored; AIV falls back to defaults. Check logs if behavior is unexpected.

---

## See Also

- [TUTORIAL.md](TUTORIAL.md) — Step-by-step guide with examples and testing
- [EXAMPLE-WORKFLOW.md](EXAMPLE-WORKFLOW.md) — How AIV works with Git flow
