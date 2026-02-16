# AIV Tutorial: Step-by-Step Guide

A guide to AIV (Automated Integrity Validation): what it is, how it works, how to configure it, and how to test it.

**Author:** Vaquar Khan

---

## Part 1: What Is This Tool?

AIV is a technical gate that validates code quality before it reaches human reviewers. It runs on pull request diffs and checks:

1. **Logic density** — Does the code have enough real logic vs. scaffolding?
2. **Design compliance** — Does it follow your project's design rules?
3. **Invariants** — Does it hold up under property-based testing?

It catches code that passes syntax checks but is logically thin, architecturally wrong, or fragile at the edges.

---

## Part 2: How It Works

### Step-by-Step Flow

1. **Checkout** — AIV gets your repo and the git diff between base ref (e.g. `origin/main`) and head ref (e.g. `HEAD`).

2. **Density gate** — For each changed Java file:
   - Parses the AST (Abstract Syntax Tree)
   - Computes Logic Density Ratio (control flow + transforms vs. structure)
   - Computes Shannon entropy
   - Fails if LDR < 0.25 or entropy < 3.8 (configurable)

3. **Design gate** — For each changed Java file:
   - Loads rules from `.aiv/design-rules.yaml`
   - Checks if code contains forbidden patterns
   - Checks if code contains required patterns (when keywords match)
   - Fails on violation

4. **Invariant gate** — Runs template-based property checks (jqwik-ready).

5. **Report** — Prints pass/fail per gate and exits with 0 (pass) or 1 (fail).

### Pipeline Diagram

```
PR diff → Density gate → Design gate → Invariant gate → Report
              ↓                ↓               ↓
           (fail?)         (fail?)         (fail?)
              ↓                ↓               ↓
           Exit 1          Exit 1          Exit 1
```

---

## Part 3: Configuration — Free Mode (Default)

Free mode uses Lucene + YAML rules. No API keys, no paid services.

### Step 1: Create config directory

```bash
mkdir -p .aiv
```

### Step 2: Create config.yaml

Create `.aiv/config.yaml`:

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

- **keywords** — Constraint applies only when file content or path contains any keyword. Empty = applies to all Java files.
- **forbidden_calls** — Substring match in file content causes fail.
- **required_calls** — When constraint applies, file must contain all of these.

### Step 4: Run AIV

```bash
mvn clean package
java -jar aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar --diff origin/main
```

---

## Part 4: Configuration — With Cost (Optional, Future)

You can add paid plugins for RAG and LLM-based design validation and test synthesis. This requires API keys.

### When to use paid mode

- You want semantic design validation against RFCs (RAG + LLM)
- You want LLM-generated property tests

### How to enable (when plugins exist)

Add to `.aiv/config.yaml`:

```yaml
design:
  mode: rag
  provider: openai
  embedding_model: text-embedding-3-small
  judge_model: gpt-4o-mini

invariant:
  strategy: llm_synthesis
  provider: openai
  model: gpt-4o
```

Set `OPENAI_API_KEY` in your environment or CI secrets.

### Free vs. paid comparison

| Feature            | Free                         | Paid (optional)              |
|--------------------|------------------------------|-------------------------------|
| Design validation  | Lucene + YAML rules          | RAG + LLM judge               |
| Test synthesis     | jqwik templates              | LLM-generated tests           |
| API keys           | None                         | OPENAI_API_KEY etc.           |
| Cost               | $0                           | Per API call                  |

---

## Part 5: Configuration Reference

### config.yaml keys

| Gate     | Key                 | Type   | Default | Description                    |
|----------|---------------------|--------|---------|--------------------------------|
| density  | ldr_threshold       | float  | 0.25    | Min logic density ratio        |
| density  | entropy_threshold   | float  | 3.8     | Min Shannon entropy            |
| design   | rules_path          | string | .aiv/design-rules.yaml | Path to rules file |
| invariant| —                   | —      | —       | No config yet                  |

### Disabling gates

```yaml
gates:
  - id: density
    enabled: true
  - id: design
    enabled: false
  - id: invariant
    enabled: false
```

### CLI arguments

| Argument      | Description           | Default        |
|---------------|-----------------------|----------------|
| --workspace   | Repo root path        | .              |
| --diff        | Base ref for diff     | origin/main    |
| --head        | Head ref for diff     | HEAD           |

---

## Part 6: Benefits

| Benefit                | Description                                              |
|------------------------|----------------------------------------------------------|
| Catch low-quality code | Flags verbose, boilerplate-heavy contributions early     |
| Enforce design rules   | Checks code follows RFCs and forbidden patterns         |
| Reduce review load     | Filters PRs before human review                          |
| Zero cost default      | Free mode needs no API keys or paid services             |
| CI-friendly            | Exit code 0/1, works in any CI pipeline                  |
| Fork-safe              | No secrets needed; works with fork PRs                   |

---

## Part 7: How to Test and Validate

### 7.1 Run unit tests

```bash
cd /path/to/aiv-gate
mvn test
```

All 42+ tests should pass. Exit code 0 = success.

### 7.2 Run AIV on the repo itself

```bash
mvn clean package
java -jar aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar --diff origin/main
```

Expected: `Overall: PASS` and exit code 0.

### 7.3 Test with a failing change (density)

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

### 7.4 Test with a design violation

Add a forbidden pattern to `.aiv/design-rules.yaml`:

```yaml
constraints:
  - id: test
    keywords: []
    forbidden_calls: [System.exit]
    required_calls: []
```

Create a Java file that calls `System.exit(0)`. Run AIV. It should fail with "Forbidden call".

### 7.5 Test in CI

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

### 7.6 Validate config loading

Create `.aiv/config.yaml` with invalid YAML or disable all gates. Run AIV. It should either use defaults or skip gates as configured.

---

## Part 8: Quick Reference

### Minimal setup (free)

```bash
mkdir -p .aiv
# Optional: add .aiv/config.yaml and .aiv/design-rules.yaml
java -jar aiv-cli.jar --diff origin/main
```

### Full config (free)

```yaml
# .aiv/config.yaml
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

### Troubleshooting

| Issue                    | Action                                              |
|--------------------------|-----------------------------------------------------|
| Too many false positives | Lower ldr_threshold or entropy_threshold            |
| Design rules too strict  | Add keywords to narrow when constraints apply       |
| Config not loading       | Check path: `.aiv/config.yaml` in repo root         |
| No Java files in diff    | All gates pass (nothing to check)                   |

---

## Next Steps

- [DEVELOPER-CONFIGURATION.md](DEVELOPER-CONFIGURATION.md) — Full config reference
- [TUTORIAL-APACHE-SPARK.md](TUTORIAL-APACHE-SPARK.md) — Apache Spark demo with Jira/SPIP
- [AIV-ARCHITECTURE-BRAINSTORM.md](AIV-ARCHITECTURE-BRAINSTORM.md) — Architecture and design
