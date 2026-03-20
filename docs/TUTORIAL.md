# AIV Tutorial: Step-by-Step Guide

This guide walks you through AIV: what it does, how it works, how to configure it, and how to test it. Works with any project (Java, Python, Go, Rust, and more). No prior experience required.

**Author:** Vaquar Khan

---

## Part 1: What Is This Tool?

AIV runs on pull request diffs and checks code before it reaches human reviewers. It does not run tests or compile your project. It analyzes the changed files and applies a set of gates:

1. **Logic density** — Does the code have enough real logic, or is it mostly scaffolding? Empty classes, getters and setters with no behavior, and copy-paste boilerplate get flagged.

2. **Design compliance** — Does the code follow your project's rules? You define forbidden patterns (for example, do not use `System.exit`) and required patterns (for example, snapshot expiration must use the ExpireSnapshots API).

3. **Dependency validation** — Are new imports in Java and Python files declared in your lockfile? This catches typos and supply-chain risks where someone registers a fake package name.

4. **Invariant checks** — Placeholder for property-based tests. The gate passes by default; real invariant checks run in your project's test phase.

5. **Doc integrity** — Validates documentation files for path existence, cross-references, required mentions, and command completeness. Opt-in via `--include-doc-checks` or config with `auto: true`.

AIV works with Java, Python, Go, Rust, Kotlin, Scala, JavaScript, TypeScript, C, C++, Ruby, and shell. The density gate runs full logic checks on Java only; other languages get entropy checks. Design and dependency checks apply to whatever languages you configure.

No API keys or paid services are required. Everything runs locally in your CI.

---

## Part 2: How It Works

### Step-by-Step Flow

1. **Checkout** — AIV reads your repo and computes the git diff between the base ref (for example, `origin/main`) and the head ref (for example, `HEAD`).

2. **Skip check** — If any commit message in the PR contains `/aiv skip` or `aiv skip`, all gates are skipped and AIV reports pass.

3. **Density gate** — For each changed Java file:
   - Parses the Abstract Syntax Tree
   - Computes Logic Density Ratio (control flow and transforms vs. structure)
   - Computes Shannon entropy
   - Skips when the change is a refactor (net negative lines) or when the author is in the trusted list
   - Fails if LDR is below 0.25 or entropy is below 3.8 (configurable)

4. **Design gate** — For each changed file matching your configured extensions:
   - Loads rules from `.aiv/design-rules.yaml`
   - Checks if the code contains forbidden patterns
   - Checks if the code contains required patterns when keywords match
   - Fails on violation

5. **Dependency gate** — For Java and Python files:
   - Extracts imports from changed files
   - Compares against `pom.xml` (Java) or `requirements.txt` / `pyproject.toml` (Python)
   - Fails on unknown imports unless they are in the whitelist

6. **Invariant gate** — Runs template-based property checks (jqwik-ready). Passes by default.

7. **Doc integrity gate** — When enabled, validates .md, .txt, .rst files for path existence, cross-refs, required mentions, command completeness. With `auto: true`, runs only when diff has doc files.

8. **Report** — Outputs pass or fail per gate via logging and exits with 0 (pass) or 1 (fail). GitHub workflow posts the report as a PR comment.

### Pipeline Diagram

```
PR diff → Skip check → Density → Design → Dependency → Invariant → Doc Integrity → Report
              ↓            ↓         ↓          ↓           ↓
           (skip?)     (fail?)   (fail?)    (fail?)     (fail?)
              ↓            ↓         ↓          ↓           ↓
           Exit 0      Exit 1    Exit 1     Exit 1      Exit 1
```

---

## Part 3: Configuration — Default Mode

Default mode uses YAML rules for design compliance. No API keys, no paid services.

### Step 1: Create the config directory

```bash
mkdir -p .aiv
```

### Step 2: Create config.yaml

Create `.aiv/config.yaml`:

```yaml
# Optional: skip generated paths
# exclude_paths:
#   - "**/generated/**"
#   - "**/*.pb.java"

gates:
  - id: density
    enabled: true
    config:
      ldr_threshold: 0.25
      entropy_threshold: 3.8
      refactor_net_loc_threshold: -50
      trusted_authors: []

  - id: design
    enabled: true
    config:
      rules_path: .aiv/design-rules.yaml

  - id: dependency
    enabled: true
    config:
      whitelist: []

  - id: invariant
    enabled: true

  - id: doc-integrity
    enabled: false
    config:
      rules_path: .aiv/doc-rules.yaml
      auto: true
```

- **ldr_threshold** — Minimum logic density ratio. Lower values allow more scaffolding.
- **entropy_threshold** — Minimum Shannon entropy. Lower values allow more repetitive code.
- **refactor_net_loc_threshold** — When the change removes more lines than it adds (net lines less than or equal to this value), the density gate skips. Default -50 means a change that removes at least 50 more lines than it adds is treated as a refactor.
- **trusted_authors** — List of committer email addresses that bypass the density check. Example: `[committer@example.org]`.

### Step 3: Create design-rules.yaml (optional)

Create `.aiv/design-rules.yaml` for design constraints:

```yaml
constraints:
  - id: no-serialization
    keywords: []
    forbidden_calls:
      - implements Serializable
    required_calls: []

  - id: snapshot-expiration
    keywords: [ExpireSnapshots]
    forbidden_calls: [table.removeSnapshots]
    required_calls: [ExpireSnapshots]
```

- **keywords** — The constraint applies only when the file content or path contains any keyword. Empty means it applies to all matched files. Matching is case-insensitive.
- **forbidden_calls** — Substring match in file content (case-insensitive) causes fail.
- **required_calls** — When the constraint applies, the file must contain all of these (case-insensitive).

### Step 4: Run AIV

```bash
mvn clean package
java -jar aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar --diff origin/main
```

---

## Part 4: Human Override and Exceptions

### Skip all gates

Add `/aiv skip` or `aiv skip` to any commit message in the pull request. AIV skips all checks and reports pass. Use this when you need to merge urgently and will fix issues later.

### Refactoring exception

When a change removes more lines than it adds (net negative lines), the density gate skips the logic-density check. This avoids flagging legitimate refactors. Configure the threshold with `refactor_net_loc_threshold` (default -50).

### Trusted authors

List committer email addresses in `trusted_authors` under the density gate config. Those authors bypass the density check. Use this for core maintainers whose contributions you trust.

---

## Part 5: Configuration Reference

### config.yaml keys

| Gate      | Key                         | Type   | Default | Description                              |
|-----------|-----------------------------|--------|---------|------------------------------------------|
| density   | ldr_threshold               | float  | 0.25    | Minimum logic density ratio              |
| density   | entropy_threshold           | float  | 3.8     | Minimum Shannon entropy                  |
| density   | refactor_net_loc_threshold  | int    | -50     | Skip density when net lines <= this     |
| density   | trusted_authors             | list   | —       | Author emails that bypass density       |
| design    | rules_path                  | string | .aiv/design-rules.yaml | Path to rules file              |
| design    | file_extensions             | list   | All common | Extensions to validate                |
| dependency| whitelist                   | list   | —       | Package names allowed without lockfile  |
| invariant | —                           | —      | —       | No config yet                           |
| doc-integrity | rules_path, auto        | .aiv/doc-rules.yaml | Path to doc rules; auto = run only when diff has doc files |

### Disabling gates

```yaml
gates:
  - id: density
    enabled: true
  - id: design
    enabled: false
  - id: dependency
    enabled: false
  - id: invariant
    enabled: false
```

### CLI arguments

| Argument    | Description       | Default        |
|-------------|-------------------|----------------|
| --workspace | Repo root path    | .              |
| --diff      | Base ref for diff | origin/main    |
| --head      | Head ref for diff | HEAD           |
| --include-doc-checks | Enable doc-integrity gate for this run | off |

---

## Part 6: Pain Areas and How Features Address Them

| Pain Area | What Happens | Feature |
|-----------|--------------|---------|
| Reviewer overload | Too many PRs; maintainers spend time on low-value contributions | Density gate filters boilerplate before human review |
| Low-quality code | Code that looks correct but is mostly scaffolding or empty | Density gate (LDR and entropy) flags it |
| Design drift | Code violates architecture, RFCs, or forbidden patterns | Design gate enforces YAML rules |
| Wrong API usage | Deprecated APIs, wrong patterns (e.g. in-memory list vs ExpireSnapshots) | Design gate: forbidden_calls and required_calls |
| Unknown imports | Typos, imports not in lockfile, supply-chain risk | Dependency gate validates vs pom.xml and requirements.txt |
| Fragile edge-case code | Passes example tests but fails on boundary inputs | Invariant gate (property-based tests) |
| Urgent merges | Need to bypass checks for hotfix | `/aiv skip` in commit message |
| Refactors flagged | Legitimate refactors remove lines; density would fail | Refactor exception (net LOC threshold) |
| Core maintainer friction | Trusted committers get unnecessary density failures | Trusted authors bypass |

---

## Part 7: Benefits Summary

| Benefit                  | Description                                              |
|--------------------------|----------------------------------------------------------|
| Catch low-quality code   | Flags verbose, boilerplate-heavy contributions early     |
| Enforce design rules     | Checks code follows RFCs and forbidden patterns          |
| Reduce review load        | Filters PRs before human review                          |
| Dependency validation    | Catches unknown imports and supply-chain risks           |
| Zero cost default        | Default mode needs no API keys or paid services           |
| CI-friendly              | Exit code 0/1, works in any CI pipeline                   |
| Fork-safe                | No secrets needed; works with fork PRs                    |

---

## Part 8: How to Test and Validate

### 8.1 Run unit tests

```bash
cd /path/to/aiv-gate
mvn test
```

All tests should pass. Exit code 0 means success.

### 8.2 Run AIV on the repo itself

```bash
mvn clean package
java -jar aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar --diff origin/main
```

Expected: `Overall: PASS` and exit code 0.

### 8.3 Test with a failing change (density)

Create a Java file with very low logic density:

```java
// test/LowDensity.java
public class LowDensity {
    public void foo() {
        // empty
    }
}
```

Run AIV. It should fail with a message like "Low logic density" or "Low entropy".

### 8.4 Test with a design violation

Add a forbidden pattern to `.aiv/design-rules.yaml`:

```yaml
constraints:
  - id: test
    keywords: []
    forbidden_calls: [System.exit]
    required_calls: []
```

Create a Java file that calls `System.exit(0)`. Run AIV. It should fail with "Forbidden call".

### 8.5 Test the skip override

Add `/aiv skip` to a commit message. Run AIV. It should pass regardless of other changes.

### 8.6 Test in CI

Add to `.github/workflows/ci.yml`:

```yaml
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
      - run: mvn clean package -DskipTests
      - run: java -jar aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar --diff origin/${{ github.base_ref }}
```

Push and verify the AIV job runs and reports correctly.

### 7.7 Validate config loading

Create `.aiv/config.yaml` with invalid YAML or disable all gates. Run AIV. It should either use defaults or skip gates as configured.

---

## Part 9: Quick Reference

### Minimal setup (default mode)

```bash
mkdir -p .aiv
# Optional: add .aiv/config.yaml and .aiv/design-rules.yaml
java -jar aiv-cli.jar --diff origin/main
```

### Full config (default mode)

```yaml
# .aiv/config.yaml
gates:
  - id: density
    enabled: true
    config:
      ldr_threshold: 0.25
      entropy_threshold: 3.8
      refactor_net_loc_threshold: -50
      trusted_authors: [committer@example.org]
  - id: design
    enabled: true
    config:
      rules_path: .aiv/design-rules.yaml
  - id: dependency
    enabled: true
    config:
      whitelist: []
  - id: invariant
    enabled: true
```

### Troubleshooting

| Issue                    | Action                                              |
|--------------------------|-----------------------------------------------------|
| Too many false positives | Lower ldr_threshold or entropy_threshold            |
| Design rules too strict  | Add keywords to narrow when constraints apply       |
| Config not loading       | Check path: `.aiv/config.yaml` in repo root         |
| No Java files in diff    | All gates pass (nothing to check)                   |
| Refactor flagged         | Lower refactor_net_loc_threshold (e.g. -30)         |

---

## Next Steps

- [FEATURES.md](FEATURES.md) — Implemented features
- [DEVELOPER-CONFIGURATION.md](DEVELOPER-CONFIGURATION.md) — Full config reference
- [TUTORIAL-APACHE-SPARK.md](TUTORIAL-APACHE-SPARK.md) — Apache Spark demo with Jira/SPIP
