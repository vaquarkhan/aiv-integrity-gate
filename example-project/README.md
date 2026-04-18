# AIV Example Project

Reference implementation for integrating AIV in a repository with minimal setup. It includes gate configuration, workflow wiring, and sample code paths that demonstrate both passing and failing outcomes.

**Author:** Vaquar Khan

---

## Scope

This example provides:

- `.aiv` configuration and design rules
- GitHub Actions workflow integration
- representative Java files for design-gate verification

---

## Execution Model

On push or pull request, GitHub Actions runs the workflow in `.github/workflows/aiv.yml`, builds the AIV CLI, evaluates the diff, and publishes status checks.

---

## Expected Outcomes

| Scenario | Change | Result |
|----------|--------|--------|
| Pass | Application and utility changes that satisfy rules | Successful AIV check |
| Fail | Any change that introduces `System.exit` | Failed design gate |

The rule set in `.aiv/design-rules.yaml` prohibits `System.exit`.

---

## Validate in CI

- [GitHub Actions](https://github.com/vaquarkhan/aiv-integrity-gate/actions) for workflow runs
- [Commit history](https://github.com/vaquarkhan/aiv-integrity-gate/commits/main) for per-commit status

---

## Local Validation

From the repository root:

```bash
# Windows
scripts\validate-example.bat

# Linux or macOS
./scripts/validate-example.sh
```

Manual invocation:

```bash
mvn -B -ntp clean verify -pl aiv-cli -am
java -jar aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar --workspace . --diff origin/main
```

---

## Project Layout

```text
example-project/
├── .aiv/
│   ├── config.yaml
│   ├── design-rules.yaml
│   └── doc-rules.yaml
├── .github/workflows/
│   └── aiv.yml
├── src/main/java/com/example/
│   ├── App.java
│   ├── Calculator.java
│   └── BadExample.java
└── pom.xml
```

---

## Test Command

```bash
mvn test
```
