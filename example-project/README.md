# AIV Example Project

This is the smallest layout we could get away with while still looking like a real repo: config under `.aiv`, a workflow under `.github`, and enough code to show a pass and a fail. Use it when you want a hands-on demo without touching production.

**Author:** Vaquar Khan

---

## How It Works

1. You run `git push` or open a Pull Request.
2. GitHub Actions starts a temporary machine.
3. AIV builds and runs on your changed files.
4. If rules pass: green check. If rules fail: red X.

---

## Where the Code Runs

AIV does not deploy to a server. It runs on GitHub's machines when you push.

1. You push from your computer to GitHub.
2. GitHub runs the workflow defined in `.github/workflows/aiv.yml`.
3. The workflow checks out the code, builds AIV, runs the CLI on the diff, and reports pass or fail.
4. The temporary machine is destroyed when the run finishes.

Your Java code stays in the GitHub repo. When you push, GitHub runs AIV on a temporary VM. No long-running server.

---

## Pass vs Fail Examples

| Scenario | What you push | AIV result |
|----------|---------------|------------|
| Pass | Good code (Calculator, App) | Green check |
| Fail | Code with `System.exit` | Red X — design rule violated |

The design rule in `.aiv/design-rules.yaml` forbids `System.exit`. If your code contains it, AIV fails.

---

## Validate

| Link | What you see |
|------|--------------|
| [GitHub Actions](https://github.com/vaquarkhan/aiv-integrity-gate/actions) | All AIV runs — green (pass) or red (fail) |
| [Commits](https://github.com/vaquarkhan/aiv-integrity-gate/commits/main) | Each commit with its AIV status |

---

## Run AIV Locally

From the parent `aiv-gate` repo root:

```bash
# Windows
scripts\validate-example.bat

# Linux or Mac
./scripts/validate-example.sh
```

Or manually:

```bash
mvn -B -ntp clean verify -pl aiv-cli -am
cd aiv-cli/target
java -jar aiv-cli-1.0.0-SNAPSHOT.jar --workspace /path/to/aiv-gate --diff origin/main
```

---

## Project Structure

```
example-project/
├── .aiv/
│   ├── config.yaml          Gate settings (density, design, invariant, doc-integrity)
│   ├── design-rules.yaml    Forbidden: System.exit, Serializable
│   └── doc-rules.yaml       Doc integrity rules (optional)
├── .github/workflows/
│   └── aiv.yml              Runs AIV on push and PR
├── src/main/java/com/example/
│   ├── App.java
│   ├── Calculator.java
│   └── BadExample.java      Contains System.exit, so AIV fails
├── pom.xml
└── README.md (this file)
```

---

## Run Tests

```bash
mvn test
```
