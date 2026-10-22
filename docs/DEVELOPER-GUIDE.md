# Developer guide

How to build, run, test, and extend AIV Integrity Gate. For the complete consumer lab that exercises all 16 gates, use [HELLO-WORLD.md](HELLO-WORLD.md). For profile/module selection, use [MODULE-ADOPTION-GUIDE.md](MODULE-ADOPTION-GUIDE.md). For every configuration key, see [DEVELOPER-CONFIGURATION.md](DEVELOPER-CONFIGURATION.md).

**Author:** Viquar Khan

---

## 1. Prerequisites

| Tool | Notes |
|---|---|
| JDK 17 or newer | Temurin or Corretto |
| Maven 3.9 or newer | On `PATH`, or call `mvn.cmd` by full path on Windows |
| Git | Required for real diffs |
| Python 3 (optional) | Enables live Python syntax checks; absent means Python files are skipped by the syntax gate and the report says so |

## 2. Build

```bash
git clone https://github.com/vaquarkhan/aiv-integrity-gate.git
cd aiv-integrity-gate
mvn clean verify
```

`verify` runs every unit test and enforces 100 percent line coverage per module with JaCoCo. If the check fails, open `MODULE/target/site/jacoco/index.html` and cover the red lines. The shaded CLI is written to `aiv-cli/target/aiv-cli-<version>.jar`.

Faster loops while working on one module:

```bash
mvn -pl aiv-plugin-security -am verify          # one gate with coverage
mvn -pl aiv-cli -am -DskipTests package         # rebuild the CLI jar only
```

On Windows PowerShell, redirect Maven output to a file and read the tail; the console truncates long builds:

```powershell
mvn -B clean verify *> build.log; Get-Content build.log -Tail 30
```

---

## 3. Hello world: your first gated repository, step by step

This walkthrough creates a small Git repository, installs AIV, makes a change that a coding agent typically produces, and shows what each gate reports. Every step is a real command; no step depends on a GitHub account.

### Step 1: create the repository

```bash
mkdir hello-aiv && cd hello-aiv
git init -q
git config user.email dev@example.com
git config user.name dev
mkdir -p src/main/java/app src/test/java/app
cat > src/main/java/app/Greeter.java <<'EOF'
package app;

public class Greeter {
    public String greet(String name) {
        return "Hello, " + name;
    }
}
EOF
cat > src/test/java/app/GreeterTest.java <<'EOF'
package app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GreeterTest {
    @Test
    void greets() {
        assertEquals("Hello, Ada", new Greeter().greet("Ada"));
    }
}
EOF
printf 'requests==2.32.3\n' > requirements.txt
git add . && git commit -qm "Initial commit"
git branch -M main
```

### Step 2: install the AIV CLI

Build the current source jar so this walkthrough includes schema-v2 verify, immutable policy, audit/report/proposal, and AI-DLC behavior:

```bash
mvn -pl aiv-cli -am clean package
export AIV_JAR="$(pwd)/aiv-cli/target/aiv-cli-2.0.jar"
java -jar "$AIV_JAR" verify --capabilities
```

### Step 3: bootstrap the configuration

```bash
java -jar aiv-cli.jar init --workspace .
cat .aiv/config.yaml
```

`init` sniffs the languages present and writes `.aiv/config.yaml` plus `.aiv/design-rules.yaml`. Hard gates (syntax, invariant, dependency, package-existence, security, test-adequacy, design) are on; soft signals (density, cohesion, duplication) are advisory. Commit the config so it lives on the protected branch:

```bash
git add .aiv && git commit -qm "Add AIV configuration"
```

### Step 4: a clean change passes

```bash
git checkout -qb feature/polite
sed -i 's/"Hello, "/"Hello there, "/' src/main/java/app/Greeter.java
sed -i 's/Hello, Ada/Hello there, Ada/' src/test/java/app/GreeterTest.java
git commit -qam "Make greeting more polite"
java -jar aiv-cli.jar --workspace . --diff main
```

Expected: `Overall: PASS`, exit code 0. The production file changed together with its test, so `test-adequacy` is satisfied.

### Step 5: a typical agent paste fails, with the reason

Create a second branch with the kinds of defects AIV is built for:

```bash
git checkout -q main && git checkout -qb feature/agent-paste
cat > src/main/java/app/Formatter.java <<'EOF'
package app;

import com.fasterxml.jackson.databind.ObjectMapper;

public class Formatter {
    // ... existing code unchanged ...
    public String format(String s) {
        String apiKey = "AKIAABCDEFGHIJKLMNOP";
        return s.trim();
    }
}
EOF
cat > src/test/java/app/FormatterTest.java <<'EOF'
package app;

import org.junit.jupiter.api.Test;

class FormatterTest {
    @Test
    void formats() {
        new Formatter().format(" x ");
    }
}
EOF
printf 'requests==2.32.3\nghost-tensor-utils==1.0\n' > requirements.txt
git add . && git commit -qm "Add formatter"
java -jar aiv-cli.jar --workspace . --diff main --output-json aiv-report.json
echo "exit=$?"
```

Expected findings (exit code 1):

| Gate | Rule | Why |
|---|---|---|
| invariant | `invariant.ai-edit-artifact` | `// ... existing code unchanged ...` is an elision marker left by an editing agent |
| security | `security.aws-key` | A credential literal on an added line |
| test-adequacy | `test-adequacy.assertion-free-test` | `formats()` calls code but asserts nothing |
| package-existence | `package-existence.unknown-package` | `ghost-tensor-utils` does not exist on PyPI (requires network; offline runs report `UNVERIFIED` instead) |

Open `aiv-report.json` to see the same findings with file and line numbers. Run `java -jar aiv-cli.jar explain invariant` for the offline help of any gate.

### Step 6: fix, re-run, and see the gate clear

Remove the elision comment, replace the literal key with a configuration lookup, add `assertEquals("x", ...)` to the test, remove the phantom package, and run again. The report returns to `PASS`.

### Step 7: tune the configuration for your repository

Every gate reads its own `config` map. Examples of common adjustments:

```yaml
gates:
  - id: invariant
    enabled: true
    config:
      placeholder_words: [TBD, FIXME, XXX, HACK]
      extra_artifact_patterns: ["TODO: implement actual logic"]
  - id: security
    enabled: true
    config:
      allow_patterns: ["src/test/resources/fixtures/"]
      extra_patterns:
        internal-token: "\\bINT-[A-Z0-9]{24}\\b"
  - id: package-existence
    enabled: true
    config:
      allow: [internal-sdk]
      pypi_url: https://mirror.example.com/simple/
  - id: duplication
    enabled: true
    severity: warn
    config:
      min_lines: 8
```

The full key list is in [DEVELOPER-CONFIGURATION.md](DEVELOPER-CONFIGURATION.md).

### Step 8: try the bypasses and watch them fail

```bash
git commit --allow-empty -qm "aiv skip"
java -jar aiv-cli.jar --workspace . --diff main
```

The report prints `[SKIP-IGNORED]` because `skip_allowlist` is empty. Now weaken the config on the branch:

```bash
printf 'schema_version: 1\ngates:\n  - id: security\n    enabled: false\n' > .aiv/config.yaml
git commit -qam "Disable security gate"
java -jar aiv-cli.jar --workspace . --diff main
```

AIV still reports the secret, because it reads the configuration from `main`, and prints `[CONFIG-CHANGE] .aiv/config.yaml` so the reviewer sees the attempt.

### Step 9: run it in GitHub Actions with the AI-SLOP label and comment

```yaml
name: AIV
on: [pull_request]
permissions:
  contents: read
  pull-requests: write
jobs:
  aiv:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5
        with: { fetch-depth: 0 }
      - uses: vaquarkhan/aiv-integrity-gate@main
        with:
          base-ref: origin/${{ github.base_ref }}
          cli-jar-url: https://artifacts.example/aiv/aiv-cli-compatible.jar
          label-ai-slop: 'true'
          comment-ai-slop: 'true'
```

When selected findings exist, the PR receives the `AI-SLOP` label and one comment that lists each finding with its rule, location, severity and a fixed remediation hint. When the next push clears the findings, the label and the comment are removed. The comment text states that AIV reports deterministic evidence and does not determine authorship. Configure which findings qualify with `ai_slop_label_gates` and `ai_slop_label_rules`.

### Step 10: run it before every commit

```yaml
# .pre-commit-config.yaml
repos:
  - repo: https://github.com/vaquarkhan/aiv-integrity-gate
    rev: v2.0
    hooks:
      - id: aiv-gate
```

### Step 11: fold a tool you already trust into the same receipt

AIV does not re-implement ruff, semgrep or gitleaks. The `external` gate runs any command that writes SARIF and reports its results as `external.<tool>.<rule>` next to the built-in findings, under the same base-ref configuration. Add this to `.aiv/config.yaml` on `main` (requires `pip install ruff`):

```yaml
  - id: external
    enabled: true
    severity: warn
    config:
      tools:
        - id: ruff
          command: ["ruff", "check", "--output-format", "sarif", "--exit-zero", "{files}"]
          sarif: stdout
          file_extensions: [.py]
          on_missing: skip
```

Add a Python file with an unused import on a branch and run the gate again. The report shows `external.ruff.F401 <file>:<line>` with ruff's own message. A machine without ruff prints `SKIPPED ruff: tool not available` in the summary instead of failing; set `on_missing: fail` when the tool is mandatory in CI. For Apache Airflow repositories, `aiv init --preset airflow` ships this wiring for ruff's `AIR` rules together with the `airflow` rule pack; see [AIRFLOW-TUTORIAL.md](AIRFLOW-TUTORIAL.md).

### Step 12: write your own rule instead of waiting for a gate

The `rules` gate is a generic engine; a framework check is a few lines of YAML, not a Java module. Put this in `.aiv/rules/team.yaml` on `main` and enable the gate with `rules_files: [".aiv/rules/*.yaml"]`:

```yaml
rules:
  - id: operator-needs-retries
    kind: call
    paths: ["dags/**/*.py"]
    call: "(?<![\\w.])\\w+Operator"
    require_arg: retries
    message: "{value} has no retries=; transient failures will fail the run"
  - id: hollow-handler
    kind: hollow-function
    paths: ["**/*.java"]
    function: "handle\\w*"
    message: "{value} is a stub"
```

Run AIV on a branch that adds `BashOperator(task_id="x", bash_command="...")` and the report shows `rules.operator-needs-retries dags/<file>:<line>`. The same three rule kinds (`pattern`, `call`, `hollow-function`) are what the shipped `airflow` pack is made of; read it at `aiv-plugin-rules/src/main/resources/rulepacks/airflow.yaml` and `aiv explain rules` for the schema summary.

### Step 13: call AIV from an agent, an MCP server, or AI-DLC

`aiv verify` is the language-neutral entry point: it reads one JSON request and writes one JSON verdict to stdout (diagnostics go to stderr), so any tool can call it the same way.

```bash
echo '{"artifact":{"files":[{"path":"src/main/java/app/Formatter.java","changeType":"MODIFIED","content":"class Formatter {}"}]}}' \
  | java -jar aiv-cli.jar verify --workspace . --audit-log verify-audit.log
```

The response carries `verdict`, per-gate `findings`, an `evidence_digest` (SHA-256 of the request, so the verdict is tied to its inputs), and an `audit.record_hash`. Verify the chain later with `aiv audit verify verify-audit.log --expect-head <record_hash>`. Use `--policy-ref origin/main` to read the gate policy from a trusted ref. Full contract: [MCP.md](MCP.md).

To wire AIV into an [AI-DLC](https://awslabs.github.io/aidlc-workflows/) workflow as a deterministic verification sensor, point the shipped sensor wrapper at the jar:

```bash
export AIV_JAR=/path/to/aiv-cli.jar
python3 integrations/aidlc/aiv_aidlc_sensor.py --file src/main/java/app/Formatter.java
```

Install routes (plugin or manual sensor): [integrations/aidlc/](../integrations/aidlc/) and [AIDLC.md](AIDLC.md).

---

## 4. Extending AIV with a new gate

1. Create a module `aiv-plugin-<name>` with a `pom.xml` that depends on `aiv-api`.
2. Implement `QualityGate`: `getId()` and `evaluate(AIVContext)`. Read settings from `context.getConfig().findGate(getId())`. Return `GateResult.pass`, `GateResult.fail` (blocks CI) or `GateResult.advisory`. Attach a `Finding` with a stable rule id, file and line for every problem.
3. Register the class in `src/main/resources/META-INF/services/io.github.vaquarkhan.aiv.port.QualityGate`.
4. Add the module to the root `pom.xml` (`<modules>` and `<dependencyManagement>`) and as a dependency of `aiv-cli`.
5. Decide the default: a gate that should run only when configured belongs in `Orchestrator.DEFAULT_OFF_GATES`.
6. Add `aiv-cli/src/main/resources/explain/<id>.md` so `aiv explain <id>` works offline.
7. Write tests until the module reaches 100 percent line coverage. Use package-private seams (for example a `Walker` or `StatusFetcher` interface) instead of mocking frameworks; the repository does not use mocks in gate code.
8. Document the gate and its keys in [DEVELOPER-CONFIGURATION.md](DEVELOPER-CONFIGURATION.md).

Design rules for gates:

- Scope to added lines whenever a unified diff is available, so pre-existing code is never re-flagged.
- When a check cannot run (missing toolchain, unreachable service), say so in the message. Never pass silently.
- Hard-block only on defects that are objective and reproducible. Everything else is advisory.
- Do not infer authorship. AIV reports evidence about the change, not about who wrote it.

---

## 5. Repository conventions

- `mvn clean verify` must be green before a pull request.
- Prefer package-private test hooks over reflection.
- Rule ids are `<gate>.<rule>` and must stay stable; downstream baselines and suppressions depend on them.
- Jev is the only gate allowed to call a decision model, and it is advisory-only by construction. No model output can block CI. Deterministic gates and publishers remain model-free.
- The dogfood configuration is [`.aiv/config.yaml`](../.aiv/config.yaml); CI runs the shaded jar on every pull request.

## 6. Troubleshooting

| Symptom | Likely cause |
|---|---|
| `jacoco:check` failed | Missed lines in new code; open the HTML report |
| Gate missing from the report | Module not on the CLI classpath, or `enabled: false` |
| `package-existence` reports `UNVERIFIED` | No network or registry unreachable; set `pypi_url`/`npm_url`/`maven_url` to a mirror or `strict: true` to fail |
| Python files never fail the syntax gate | No Python interpreter on `PATH`; set `-Daiv.python.command` |
| Label or comment not published | Missing `GITHUB_TOKEN` or `pull-requests: write`; fork PRs have a read-only token |
| `[CONFIG-CHANGE]` notice on every run | The PR edits `.aiv/`; expected, review the change deliberately |
