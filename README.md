# AIV Gate - Automated Integrity Validation

Technical gate for code integrity validation in Apache projects. Validates logic density, design compliance, and invariants. Catches logic density issues, design violations, and supports property-based invariant checks.

AIV (Automated Integrity Validation) is a technical gate for Apache and open source projects. It runs on pull request diffs to catch logic density issues, design rule violations, and low-quality code patterns before human review. No API keys required in default mode.

**Author:** Vaquar Khan

## Modules

| Module | Purpose |
|--------|---------|
| `aiv-api` | Interfaces, models, SPI |
| `aiv-core` | Orchestrator |
| `aiv-plugin-density` | Logic density + entropy gate |
| `aiv-plugin-design-lucene` | Design compliance (Lucene + YAML rules) |
| `aiv-plugin-invariant-template` | Invariant gate (template-based) |
| `aiv-adapter-git` | Git diff provider |
| `aiv-adapter-github` | Stdout report publisher |
| `aiv-cli` | CLI entry point |

## Build

```bash
mvn clean package
```

## Run

```bash
java -jar aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar --diff origin/main
```

Or via Maven:

```bash
mvn -pl aiv-cli exec:java -Dexec.args="--diff origin/main"
```

## Example Project

The [example-project/](example-project/) folder shows how AIV works with Git flow. See [docs/EXAMPLE-WORKFLOW.md](docs/EXAMPLE-WORKFLOW.md) for where the Java code runs and pass/fail examples.

Validate locally:

```bash
scripts\validate-example.bat   # Windows
./scripts/validate-example.sh  # Linux/Mac
```

Validate on GitHub: [Actions](https://github.com/vaquarkhan/aiv-integrity-gate/actions) | [Commits](https://github.com/vaquarkhan/aiv-integrity-gate/commits/main)

## Tutorial

- **[docs/TUTORIAL.md](docs/TUTORIAL.md)** — Step-by-step guide: what AIV does, how it works, free vs. paid config, and how to test
- **[docs/TEST.md](docs/TEST.md)** — Test cases and GitHub testing steps
- **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)** — Where code goes, how to deploy AIV, and how to enable it in your project
- **[docs/TUTORIAL-APACHE-SPARK.md](docs/TUTORIAL-APACHE-SPARK.md)** — Apache Spark demo: add AIV, Jira/SPIP example, end-to-end validation

## Config

See **[docs/DEVELOPER-CONFIGURATION.md](docs/DEVELOPER-CONFIGURATION.md)** for full configuration reference.

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

Create `.aiv/design-rules.yaml` for design constraints:

```yaml
constraints:
  - id: snapshot-expiration
    keywords: [ExpireSnapshots, expireSnapshots]
    forbidden_calls: [table.removeSnapshots]
    required_calls: [ExpireSnapshots]
```

## CI Integration

Add to your pipeline:

```yaml
- run: java -jar aiv-cli.jar --workspace . --diff origin/main
```

Exit code 0 = pass, 1 = fail.

## License

Apache License 2.0
