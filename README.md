# AIV Gate — Automated Integrity Validation

AIV runs on pull request diffs and checks code before it reaches human reviewers. It flags contributions that pass syntax checks but lack substance, violate project rules, or introduce dependency risks.

**Author:** Vaquar Khan

---

## Problems and Solutions

| Pain Area | What Happens | Feature That Addresses It |
|-----------|--------------|---------------------------|
| Reviewer overload | Too many PRs to review; maintainers spend time on low-value contributions | Density gate filters verbose, boilerplate-heavy code before human review |
| Low-quality contributions | Code that looks correct but is mostly scaffolding, empty classes, or copy-paste | Density gate (logic density ratio and entropy) flags it |
| Design drift | Code violates project architecture, RFCs, or forbidden patterns | Design gate enforces YAML rules (forbidden and required patterns) |
| Wrong API usage | Contributors use deprecated APIs or wrong patterns (e.g. in-memory list instead of ExpireSnapshots) | Design gate catches forbidden calls and missing required calls |
| Unknown imports | Typos in package names or imports not declared in lockfile; supply-chain risk | Dependency gate validates Java imports vs pom.xml, Python vs requirements.txt |
| Fragile edge-case code | Code passes example tests but fails on boundary inputs | Invariant gate (placeholder for property-based tests) |
| Urgent merges | Need to bypass checks for hotfix or emergency | `/aiv skip` in commit message skips all gates |
| Refactors flagged | Legitimate refactors remove more lines than they add; density gate would fail | Refactor exception: density skips when net lines <= threshold (default -50) |
| Core maintainer friction | Trusted committers get unnecessary density failures | Trusted authors bypass density check |
| Issue squatting | Contributors get assigned, then ghost or submit low-quality code | Assignment Gate: assign only after PR passes AIV |

---

## What This Tool Does

When someone opens a pull request, AIV runs a set of checks on the changed files:

1. **Density** — Does the code have enough real logic, or is it mostly scaffolding? Empty classes and copy-paste boilerplate get flagged.

2. **Design** — Does the code follow your project's rules? You define forbidden patterns (for example, do not use `System.exit`) and required patterns (for example, snapshot expiration must use the ExpireSnapshots API).

3. **Dependency** — Are new imports in Java and Python files declared in your lockfile? This helps catch typos and supply-chain attacks where someone registers a fake package name.

4. **Invariant** — Placeholder for property-based tests. The gate passes by default; real invariant checks run in your project's test phase.

AIV works with Java, Python, Go, Rust, Kotlin, Scala, JavaScript, TypeScript, C, C++, Ruby, and shell. The density gate runs full logic checks on Java only; other languages get entropy checks. Design and dependency checks apply to whatever languages you configure.

No API keys or paid services are required. Everything runs locally in your CI.

---

## Modules

| Module | Purpose |
|--------|---------|
| `aiv-api` | Interfaces, models, and extension points |
| `aiv-core` | Orchestrator that runs gates in sequence |
| `aiv-plugin-density` | Logic density and entropy checks |
| `aiv-plugin-design-lucene` | Design compliance via YAML rules |
| `aiv-plugin-dependency` | Import validation against pom.xml and requirements.txt |
| `aiv-plugin-invariant-template` | Invariant gate (passes by default) |
| `aiv-adapter-git` | Git diff provider |
| `aiv-adapter-github` | Report publisher (stdout) |
| `aiv-cli` | Command-line entry point |

---

## Build and Run

Build the project:

```bash
mvn clean package
```

Run AIV from the repo root (compare your working tree to `origin/main`):

```bash
java -jar aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar --diff origin/main
```

Or via Maven:

```bash
mvn -pl aiv-cli exec:java -Dexec.args="--diff origin/main"
```

Exit code 0 means all gates passed. Exit code 1 means at least one gate failed.

---

## Example Project

The `example-project/` directory contains a minimal setup: config files, design rules, and a GitHub Actions workflow. Use it to see how AIV behaves on real code.

Validate locally (from the parent repo):

```bash
scripts\validate-example.bat   # Windows
./scripts/validate-example.sh  # Linux or Mac
```

---

## Sample Configurations

Ready-made rule sets for common projects:

- [Apache Spark](docs/samples/apache-spark/) — Config, design rules, SparkConf, deprecated APIs
- [Apache Iceberg](docs/samples/apache-iceberg/) — Snapshot API, deprecation rules, architecture constraints
- [Apache Airflow](docs/samples/apache-airflow/) — Python rules, days_ago, eval/exec, providers

Copy the config and design-rules files into your project's `.aiv/` folder and adjust as needed.

---

## Documentation

| Document | Contents |
|----------|----------|
| [FEATURES.md](docs/FEATURES.md) | Implemented features |
| [FUTURE-ROADMAP.md](docs/FUTURE-ROADMAP.md) | Planned work and gaps |
| [TUTORIAL.md](docs/TUTORIAL.md) | Step-by-step setup, configuration, and testing |
| [DEVELOPER-CONFIGURATION.md](docs/DEVELOPER-CONFIGURATION.md) | Full configuration reference |
| [DEPLOYMENT.md](docs/DEPLOYMENT.md) | Enabling AIV in your project and CI |
| [TEST.md](docs/TEST.md) | Test cases and validation steps |
| [EXAMPLE-WORKFLOW.md](docs/EXAMPLE-WORKFLOW.md) | How the example project runs in GitHub Actions |
| [TUTORIAL-APACHE-SPARK.md](docs/TUTORIAL-APACHE-SPARK.md) | Apache Spark walkthrough |
| [TUTORIAL-APACHE-ICEBERG.md](docs/TUTORIAL-APACHE-ICEBERG.md) | Apache Iceberg walkthrough |
| [TUTORIAL-APACHE-AIRFLOW.md](docs/TUTORIAL-APACHE-AIRFLOW.md) | Apache Airflow walkthrough |
| [ASSIGNMENT-GATE.md](docs/ASSIGNMENT-GATE.md) | Proof of Work: assign only after AIV passes |

---

## Minimal Configuration

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
  - id: design
    enabled: true
    config:
      rules_path: .aiv/design-rules.yaml
  - id: dependency
    enabled: true
  - id: invariant
    enabled: true
```

Create `.aiv/design-rules.yaml` with at least one constraint:

```yaml
constraints:
  - id: snapshot-expiration
    keywords: [ExpireSnapshots, expireSnapshots]
    forbidden_calls: [table.removeSnapshots]
    required_calls: [ExpireSnapshots]
```

If you omit these files, AIV uses built-in defaults and looks for `.aiv/design-rules.yaml` when the design gate is enabled.

---

## CI Integration

Add a step to your pipeline:

```yaml
- run: java -jar aiv-cli.jar --workspace . --diff origin/main
```

The step fails the job when any gate fails. No secrets are required for the default setup.

---

## Human Override and Exceptions

**Skip all gates:** Add `/aiv skip` or `aiv skip` to any commit message in the pull request. AIV skips all checks and reports pass.

**Refactoring:** When a change removes more lines than it adds (net negative lines), the density gate skips the logic-density check. This avoids flagging legitimate refactors.

**Trusted authors:** List committer email addresses in `trusted_authors` under the density gate config. Those authors bypass the density check.

---

## License

Apache License 2.0
