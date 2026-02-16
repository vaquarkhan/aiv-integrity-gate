# AIV Example Workflow: How It Works with Git Flow

Step-by-step explanation of the example project and where the code runs.

**Author:** Vaquar Khan

---

## 1. Overview

AIV runs when you push or open a Pull Request. It checks your changed files against density, design rules, dependency imports, and invariants. No server to deploy. GitHub runs it automatically on a temporary machine.

---

## 2. Where the Code Runs

| Location | What lives there |
|----------|------------------|
| GitHub repo | Your source code (example-project, aiv-cli, etc.) |
| GitHub Actions | Temporary VM where AIV runs on each push or PR |
| Your computer | Local copy; you push from here |

There is no separate deployment. The code in the repo is what runs. When you push, GitHub:

1. Starts a fresh Ubuntu VM
2. Clones your repo into it
3. Builds AIV (`mvn package -pl aiv-cli`)
4. Runs `java -jar aiv-cli.jar` on the diff
5. Destroys the VM

---

## 3. Git Flow Diagram

```
Your computer (git push)
    |
    v
GitHub.com (aiv-integrity-gate)
    |
    v
Push / PR / Branch protection
    |
    v
.github/workflows/aiv.yml
    |
    v
GitHub Actions Runner VM
    1. Checkout
    2. Build AIV
    3. Run AIV
    4. Report
    |
    +---> PASS (Exit 0, green)
    |
    +---> FAIL (Exit 1, red X)
```

---

## 4. Pass and Fail Examples

### Pass (good code)

- **Commit:** `Docs: clarify README description`
- **Change:** README text only, no Java changes
- **AIV:** PASS (no design violations)

### Fail (bad code)

- **Commit:** `Add BadExample for demo (will fail AIV)`
- **Change:** Added `BadExample.java` with `System.exit(0)`
- **AIV:** FAIL — design rule forbids `System.exit`

### Skip override

- **Commit:** `Fix urgent bug /aiv skip`
- **Change:** Any change
- **AIV:** PASS (skip requested in commit message)

---

## 5. Validate

| Link | Purpose |
|------|---------|
| [Actions](https://github.com/vaquarkhan/aiv-integrity-gate/actions) | See all AIV runs (pass or fail) |
| [Commits](https://github.com/vaquarkhan/aiv-integrity-gate/commits/main) | See each commit with AIV status |

---

## 6. Files That Control the Flow

| File | Role |
|------|------|
| `.github/workflows/aiv.yml` | Triggers AIV on push and PR |
| `.aiv/config.yaml` | Enables density, design, dependency, invariant gates |
| `.aiv/design-rules.yaml` | Forbidden patterns (e.g. System.exit) |

---

## 7. Workflow Steps in Detail

When the workflow runs:

1. **Checkout** — GitHub checks out your branch with full history (`fetch-depth: 0`) so AIV can compute the diff.

2. **Build** — Maven builds the AIV CLI and its dependencies. The JAR is produced in `aiv-cli/target/`.

3. **Run AIV** — The CLI is invoked with `--workspace` pointing at your repo root and `--diff` pointing at the base branch. AIV reads `.aiv/config.yaml` and `.aiv/design-rules.yaml` from the workspace.

4. **Report** — AIV prints pass or fail per gate and exits with 0 or 1. GitHub uses the exit code to mark the check as passed or failed.

---

## See Also

- [example-project/README.md](../example-project/README.md) — Example project overview
- [DEPLOYMENT.md](DEPLOYMENT.md) — Deployment guide
- [TEST.md](TEST.md) — Test cases
