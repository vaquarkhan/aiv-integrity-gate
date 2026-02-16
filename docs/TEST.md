# AIV Test Plan and GitHub Testing Guide

Test cases and step-by-step instructions for validating AIV (Automated Integrity Validation) locally and with GitHub Actions.

**Author:** Vaquar Khan

---

## 0. GitHub Setup: Step-by-Step (Beginner-Friendly)

This section explains exactly where every file goes and how to configure AIV in a GitHub repo. Follow each step in order.

---

### 0.1 Where Everything Lives

Your repo root must look like this when done:

```
your-repo-name/
├── .aiv/                          ← Config folder (you create this)
│   ├── config.yaml                ← Gate settings (density, design, invariant)
│   └── design-rules.yaml          ← Forbidden/required patterns
├── .github/
│   └── workflows/
│       └── aiv.yml                ← GitHub Actions workflow (triggers AIV on PR)
├── pom.xml                        ← Your Maven project (or other files)
├── src/
└── ... (rest of your project)
```

**Important:** `.aiv` and `.github` are hidden folders (they start with a dot). On Windows, enable "Show hidden files" if you don't see them.

---

### 0.2 Step 1: Create the `.aiv` Folder

**On your computer (in your repo root):**

1. Open your project folder (e.g. `C:\Users\You\my-project` or `/home/you/my-project`).
2. Create a new folder named exactly: `.aiv`
   - **Windows:** Right-click → New → Folder → type `.aiv`
   - **Mac/Linux:** `mkdir .aiv` in terminal
3. Confirm: the path is `your-repo/.aiv/` (inside your repo root, not inside `src` or anywhere else).

---

### 0.3 Step 2: Create `config.yaml` Inside `.aiv`

1. Open the `.aiv` folder.
2. Create a new file named exactly: `config.yaml` (no `.txt`, no extra spaces).
3. Paste this content **exactly** (you can change numbers later):

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

4. Save the file.
5. **What this does:**
   - `density` — Checks if code has enough logic (not just empty classes). `ldr_threshold: 0.25` = minimum 25% logic. `entropy_threshold: 3.8` = minimum randomness (catches copy-paste).
   - `design` — Checks your rules from `design-rules.yaml`.
   - `dependency` — Validates Java imports against pom.xml and Python imports against requirements.txt.
   - `invariant` — Runs property-based checks.
   - To turn off a gate: change `enabled: true` to `enabled: false`.

---

### 0.4 Step 3: Create `design-rules.yaml` Inside `.aiv`

1. In the same `.aiv` folder, create a new file named exactly: `design-rules.yaml`
2. Paste this content (customize for your project):

```yaml
constraints:
  - id: no-system-exit
    keywords: []
    forbidden_calls:
      - System.exit
    required_calls: []

  - id: no-serialization
    keywords: []
    forbidden_calls:
      - implements Serializable
    required_calls: []
```

3. Save the file.
4. **What each part means:**
   - `id` — A name for the rule (use lowercase, hyphens).
   - `keywords` — When to apply. Empty `[]` = apply to ALL Java files. Or use `[ExpireSnapshots]` to apply only when file contains that text.
   - `forbidden_calls` — If the code contains any of these strings, AIV fails. Add your own (e.g. `System.out.println`, `Thread.sleep`).
   - `required_calls` — If the constraint applies, the code MUST contain all of these. Empty `[]` = no requirement.

**Example: rule that applies only to snapshot code:**
```yaml
  - id: use-expire-api
    keywords: [expireSnapshots, ExpireSnapshots]
    forbidden_calls: [table.removeSnapshots]
    required_calls: [ExpireSnapshots]
```
This means: if the file mentions `expireSnapshots`, it must use `ExpireSnapshots` and must NOT use `table.removeSnapshots`.

---

### 0.5 Step 4: Create the GitHub Workflow

1. Create folder: `.github` (in repo root, same level as `.aiv`).
2. Inside `.github`, create folder: `workflows`.
3. Inside `.github/workflows`, create file: `aiv.yml`
4. Paste this content **exactly**:

```yaml
name: AIV Gate
on:
  pull_request:
    branches: [main, master]

jobs:
  aiv:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17

      - name: Build project
        run: mvn clean package -DskipTests -B -q

      - name: Run AIV
        run: |
          java -jar aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar \
            --workspace . \
            --diff origin/${{ github.base_ref }}
```

5. **If your main branch is not `main` or `master`:** Change line 5 to your branch, e.g. `branches: [develop]`.
6. **If your CLI JAR has a different path:** Change `aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar` to the correct path. For the AIV project itself, this path is correct.
7. Save the file.

**What this does:** When someone opens a pull request, GitHub runs this workflow. It checks out the code, builds it, runs AIV on the diff, and fails the check if AIV fails.

---

### 0.6 Step 5: Add the Repo to GitHub and Push

**If you already have a GitHub repo:**

1. Open terminal in your repo root.
2. Run:
   ```bash
   git add .aiv .github
   git status
   ```
   You should see `.aiv/config.yaml`, `.aiv/design-rules.yaml`, `.github/workflows/aiv.yml`.
3. Run:
   ```bash
   git commit -m "Add AIV configuration and GitHub workflow"
   git push origin your-branch-name
   ```

**If you need to create a new repo:**

1. Go to https://github.com/new
2. Enter repo name (e.g. `my-aiv-test`).
3. Choose Public. Do NOT add README, .gitignore, or license (you already have files).
4. Click "Create repository".
5. In your terminal (in your project folder):
   ```bash
   git init
   git add .
   git commit -m "Initial commit with AIV"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPO_NAME.git
   git push -u origin main
   ```
   Replace `YOUR_USERNAME` and `YOUR_REPO_NAME` with your GitHub username and repo name.

---

### 0.7 Step 6: Test with a Pull Request

1. Create a new branch:
   ```bash
   git checkout -b test-aiv
   ```
2. Make a small change (e.g. add a line to README or create a test Java file).
3. Commit and push:
   ```bash
   git add .
   git commit -m "Test AIV"
   git push origin test-aiv
   ```
4. Go to your repo on GitHub.
5. You should see a yellow banner: "test-aiv had recent pushes" with a **Compare & pull request** button. Click it.
6. Click **Create pull request**.
7. Wait 1–2 minutes. A check named **AIV Gate** (or **aiv**) should appear. Click "Details" to see the log.
8. **Pass:** Green check. **Fail:** Red X — click Details to see why.

---

### 0.8 Configuration Quick Reference

| File | Path | Purpose |
|------|------|---------|
| config.yaml | `.aiv/config.yaml` | Enable/disable gates, set density thresholds |
| design-rules.yaml | `.aiv/design-rules.yaml` | Forbidden and required patterns |
| aiv.yml | `.github/workflows/aiv.yml` | Run AIV on every pull request |

| config.yaml key | What to change | Effect |
|-----------------|----------------|--------|
| `ldr_threshold` | 0.2 (looser) or 0.3 (stricter) | Logic density minimum |
| `entropy_threshold` | 3.5 (looser) or 4.0 (stricter) | Boilerplate detection |
| `enabled: false` | On any gate | Skip that gate entirely |
| `rules_path` | e.g. `.aiv/my-rules.yaml` | Use different rules file |

| design-rules.yaml | What to add | Effect |
|-------------------|-------------|--------|
| `forbidden_calls: [X]` | String to ban | Code containing X fails |
| `required_calls: [Y]` | String required | Code must contain Y when keywords match |
| `keywords: [Z]` | Scope | Rule applies only when file contains Z |

---

### 0.9 Using AIV in a Repo That Is NOT the AIV Project

The workflow in 0.5 assumes your repo **is** the AIV project (has `aiv-cli/` and builds the JAR). If you want to run AIV on a **different** repo (e.g. your Spark fork, your Java app):

**Option A: Build AIV from source in the workflow**

Replace the "Build project" and "Run AIV" steps with:

```yaml
      - name: Clone and build AIV
        run: |
          git clone https://github.com/apache/aiv-gate.git aiv-src
          cd aiv-src
          mvn clean package -DskipTests -B -q

      - name: Run AIV on this repo
        run: |
          java -jar aiv-src/aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar \
            --workspace ${{ github.workspace }} \
            --diff origin/${{ github.base_ref }}
```

(`${{ github.workspace }}` is your repo root; AIV reads `.aiv/` from there.)

**Option B: Use published AIV CLI (when available)**

```yaml
      - name: Download AIV CLI
        run: |
          curl -sL -o aiv-cli.jar \
            "https://repo.maven.apache.org/maven2/org/apache/aiv/aiv-cli/1.0.0/aiv-cli-1.0.0.jar"

      - name: Run AIV
        run: java -jar aiv-cli.jar --workspace . --diff origin/${{ github.base_ref }}
```

**Config files:** You still need `.aiv/config.yaml` and `.aiv/design-rules.yaml` in **your** repo root. AIV reads them from the workspace.

---

## 1. Test Cases Overview

| Category | Test Case | Expected Result |
|----------|-----------|-----------------|
| Unit | All JUnit tests pass | Exit 0 |
| CLI | Run AIV on clean repo | PASS, exit 0 |
| Skip | Commit message contains /aiv skip | PASS, exit 0 |
| Density | Low logic density file | FAIL, exit 1 |
| Design | Forbidden pattern in code | FAIL, exit 1 |
| Design | Required pattern missing | FAIL, exit 1 |
| Dependency | Unknown import in Java file | FAIL, exit 1 |
| Config | Invalid YAML | Graceful fallback or error |
| GitHub | PR triggers AIV job | Job runs, report visible |
| GitHub | AIV fails on bad code | Job fails, PR blocked |

---

## 2. Unit Tests

### TC-01: Run all unit tests

**Steps:**
```bash
cd /path/to/aiv-gate
mvn test
```

**Expected:**
- All tests pass (42+ tests)
- Exit code 0
- JaCoCo report generated in `target/site/jacoco/`

**Verify:**
```bash
echo $?   # Should print 0
```

---

### TC-02: Run specific module tests

**Steps:**
```bash
mvn test -pl aiv-plugin-density
mvn test -pl aiv-plugin-design-lucene
mvn test -pl aiv-cli
```

**Expected:** Each module's tests pass independently.

---

## 3. CLI and Local Integration Tests

### TC-03: Run AIV on the repo itself (no changes)

**Steps:**
```bash
mvn clean package -DskipTests
java -jar aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar --diff origin/main
```

**Expected:**
- Output shows `Overall: PASS` or gates pass for changed files
- Exit code 0

---

### TC-04: Density gate — low logic density

**Steps:**
1. Create a new file `aiv-cli/src/test/resources/LowDensity.java`:
```java
public class LowDensity {
    public void foo() {
    }
}
```
2. Run AIV:
```bash
java -jar aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar --diff origin/main
```

**Expected:**
- Density gate fails
- Message indicates low logic density or low entropy
- Exit code 1

**Cleanup:** Delete the test file after verification.

---

### TC-05: Design gate — forbidden pattern

**Steps:**
1. Add to `.aiv/design-rules.yaml`:
```yaml
constraints:
  - id: no-exit
    keywords: []
    forbidden_calls: [System.exit]
    required_calls: []
```
2. Create a Java file that calls `System.exit(0)` in the repo
3. Run AIV:
```bash
java -jar aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar --diff origin/main
```

**Expected:**
- Design gate fails
- Message indicates forbidden call `System.exit`
- Exit code 1

**Cleanup:** Revert the test file and design-rules change.

---

### TC-06: Design gate — required pattern missing

**Steps:**
1. Add to `.aiv/design-rules.yaml`:
```yaml
constraints:
  - id: must-use-expire
    keywords: [expireSnapshots, ExpireSnapshots]
    forbidden_calls: []
    required_calls: [ExpireSnapshots]
```
2. Create a Java file that mentions `expireSnapshots` but does not call `ExpireSnapshots`
3. Run AIV

**Expected:**
- Design gate fails
- Message indicates required call missing
- Exit code 1

---

### TC-06b: Skip override

**Steps:**
1. Add `/aiv skip` to a commit message in the PR (for example: `git commit -m "Fix typo /aiv skip"`).
2. Run AIV on that branch.

**Expected:**
- All gates skipped
- Exit code 0

**Cleanup:** Revert the commit or create a new commit without the skip marker.

---

### TC-07: Config loading

**Steps:**
1. Create `.aiv/config.yaml` with all gates disabled:
```yaml
gates:
  - id: density
    enabled: false
  - id: design
    enabled: false
  - id: invariant
    enabled: false
```
2. Run AIV

**Expected:**
- All gates skipped
- Exit code 0 (nothing to check)

---

## 4. GitHub Testing Steps

### TC-08: Add AIV workflow to your repo

**Steps:**
1. Create `.github/workflows/aiv.yml` in your repo:
```yaml
name: CI
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

      - run: mvn clean package -DskipTests -B -q

      - name: Run AIV Gate
        run: |
          java -jar aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar \
            --workspace . \
            --diff origin/${{ github.base_ref }}
```

2. Commit and push to a branch
3. Open a pull request against `main` or `master`

**Expected:**
- AIV job appears in the PR checks
- Job runs and completes
- Check status visible in PR (pass or fail)

---

### TC-09: Verify AIV passes on good code

**Steps:**
1. Create a branch with a small, valid change (e.g., fix a typo in README)
2. Push and open a PR
3. Wait for GitHub Actions to run

**Expected:**
- AIV job passes (green check)
- PR shows all checks passed

---

### TC-10: Verify AIV fails on bad code

**Steps:**
1. Create a branch
2. Add a Java file with `System.exit(0)` and ensure `.aiv/design-rules.yaml` forbids it (see TC-05)
3. Push and open a PR

**Expected:**
- AIV job fails (red X)
- Job logs show design gate failure
- PR is blocked until fixed (if branch protection requires AIV to pass)

---

### TC-11: Use reusable workflow (infrastructure-actions)

**Prerequisites:** AIV CLI published to Maven Central or GitHub Releases.

**Steps:**
1. In your project, create `.github/workflows/ci.yml`:
```yaml
name: CI
on:
  pull_request:
    branches: [main, master]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: mvn test

  aiv:
    uses: apache/infrastructure-actions/.github/workflows/aiv.yml@main
    with:
      base-ref: origin/${{ github.base_ref }}
```

2. Push and open a PR

**Expected:**
- `aiv` job runs via reusable workflow
- Uses published AIV CLI (no local build)

**Note:** Until AIV CLI is published, use the inline workflow from TC-08.

---

### TC-12: Fork PR (no secrets)

**Steps:**
1. Fork the repo to your account
2. Add AIV workflow (TC-08 style)
3. Create a branch in your fork, push changes
4. Open a PR from fork to upstream

**Expected:**
- AIV job runs (default mode needs no secrets)
- Works because AIV uses Lucene + YAML, not paid APIs

---

## 5. Quick Test Checklist

| # | Test | Command / Action | Pass? |
|---|------|------------------|-------|
| 1 | Unit tests | `mvn test` | |
| 2 | Build | `mvn clean package` | |
| 3 | AIV on repo | `java -jar aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar --diff origin/main` | |
| 4 | Add workflow | Create `.github/workflows/aiv.yml` | |
| 5 | PR with good code | Open PR, verify AIV passes | |
| 6 | PR with bad code | Add forbidden pattern, verify AIV fails | |

---

## 6. Troubleshooting

| Issue | Check |
|-------|-------|
| AIV job not appearing | Ensure workflow file is in `.github/workflows/` and triggers on `pull_request` |
| "No Java files in diff" | AIV passes when diff has no Java files; add a Java change to test |
| Design rules not applied | Verify `.aiv/design-rules.yaml` exists and `keywords` match your file |
| CLI not found in GitHub | Use inline build (TC-08) until AIV CLI is published |
| Fork PR fails | Ensure workflow does not use `pull_request_target` or secrets from base repo |

---

## See Also

- [TUTORIAL.md](TUTORIAL.md) — General AIV guide and testing (Part 7)
- [DEVELOPER-CONFIGURATION.md](DEVELOPER-CONFIGURATION.md) — Config reference
- [infrastructure-actions/aiv/README.md](../infrastructure-actions/aiv/README.md) — 3-line opt-in for GitHub
