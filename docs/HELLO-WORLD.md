# AIV Hello World: learn every gate end to end

This tutorial builds the current source version of AIV, creates a small multi-language lab repository, produces a clean verdict, and then exercises every gate one by one. It finishes with strict policy, JSON/SARIF, `aiv verify`, the audit chain, evidence reporting, GitHub Actions, and AI-DLC.

![AIV deployment and integration topology](images/aiv-deployment-map.png)

**Figure 1: The lab follows the same deployment path as a real repository.** You run a small local profile while learning, then move the same jar and policy into strict CI. Agent and lifecycle integrations use the same `aiv verify` engine rather than a separate implementation.

## 1. Prerequisites

- JDK 17 or newer.
- Maven 3.9 or newer.
- Git.
- Python 3 for Python syntax and the AI-DLC wrapper.
- Node and Go only if you want to exercise those external syntax validators.

This tutorial uses the source-built jar because `aiv verify`, immutable policy snapshots, the AI-DLC plugin, strict RFC JSON, and the hardened Action are next-release capabilities.

## 2. Build the current AIV jar

```bash
git clone https://github.com/vaquarkhan/aiv-integrity-gate.git aiv-source
cd aiv-source
mvn clean verify
export AIV_SOURCE="$(pwd)"
export AIV_JAR="$AIV_SOURCE/aiv-cli/target/aiv-cli-1.0.4.jar"
java -jar "$AIV_JAR" --version
cd ..
```

On Windows PowerShell:

```powershell
git clone https://github.com/vaquarkhan/aiv-integrity-gate.git aiv-source
& "C:\tools\apache-maven-3.9.11\bin\mvn.cmd" -f aiv-source\pom.xml clean verify
$env:AIV_SOURCE = (Resolve-Path aiv-source).Path
$env:AIV_JAR = Join-Path $env:AIV_SOURCE "aiv-cli\target\aiv-cli-1.0.4.jar"
java -jar $env:AIV_JAR --version
```

Expected: `aiv-cli 1.0.4`. The version remains 1.0.4 until a release preparation changes Maven metadata; source behavior is newer than the published artifact.

## 3. Create the lab repository

```bash
mkdir hello-aiv
cd hello-aiv
git init -q
git config user.name "AIV Learner"
git config user.email "learner@example.com"
mkdir -p src/main/java/app src/test/java/app docs .aiv/rules
```

Create `pom.xml`:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>example</groupId>
  <artifactId>hello-aiv</artifactId>
  <version>1.0.0</version>
  <dependencies>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.10.2</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

Create `src/main/java/app/Greeter.java`:

```java
package app;

public class Greeter {
    public String greet(String name) {
        return "Hello, " + name;
    }
}
```

Create `src/test/java/app/GreeterTest.java`:

```java
package app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GreeterTest {
    @Test
    void greets() {
        assertEquals("Hello, Ada", new Greeter().greet("Ada"));
    }
}
```

Create `README.md`:

```markdown
# Hello AIV

Build with `mvn verify`.

See [developer notes](docs/developer.md).
```

Create `docs/developer.md`:

```markdown
# Developer notes

The greeting implementation lives in `src/main/java/app/Greeter.java`.
```

## 4. Install an explicit balanced policy

Create `.aiv/config.yaml`. Every gate is named explicitly so no omitted gate inherits an unexpected default:

```yaml
schema_version: 1
fail_fast: false
exclude_paths: [target/**, build/**]
gates:
  - { id: syntax, enabled: true }
  - { id: invariant, enabled: true }
  - { id: dependency, enabled: true }
  - { id: package-existence, enabled: false }
  - { id: test-adequacy, enabled: true }
  - { id: security, enabled: true }
  - id: design
    enabled: true
    config: { rules_path: .aiv/design-rules.yaml }
  - { id: density, enabled: true, severity: warn }
  - { id: cohesion, enabled: true, severity: warn }
  - { id: duplication, enabled: true, severity: warn }
  - { id: doc-integrity, enabled: false }
  - { id: pr-metadata, enabled: false }
  - { id: license-header, enabled: false }
  - { id: external, enabled: false }
  - { id: rules, enabled: false }
  - { id: jev, enabled: false }
```

Create `.aiv/design-rules.yaml`:

```yaml
constraints:
  - id: no-process-exit
    keywords: [java]
    forbidden_calls: [System.exit]
```

Commit the trusted baseline:

```bash
git add .
git commit -m "Create clean AIV lab"
git branch -M main
```

## 5. Run the clean baseline

```bash
java -jar "$AIV_JAR" --workspace . --diff HEAD --head HEAD
```

Expected: `Overall: PASS`. Density/cohesion/duplication may report advisory information depending on the changed set, but they do not make the overall result fail in this policy.

## 6. The repeatable gate-lab pattern

For each gate:

```bash
git checkout -B gate-lab main
# make the described change
git add .
git commit -m "Exercise <gate>"
java -jar "$AIV_JAR" --workspace . --diff main --head HEAD \
  --output-json aiv-report.json --output-sarif aiv-results.sarif
git checkout main
```

Use `java -jar "$AIV_JAR" explain <gate-id>` for offline rule help. The following sections show the smallest meaningful change for every gate.

## 7. Gate 1: `syntax`

Problem solved: a file that cannot be parsed should not consume a full CI cycle.

```bash
cat > broken.json <<'EOF'
{foo: bar}
EOF
```

Expected: hard `syntax.parse`. AIV uses strict RFC 8259 JSON, so YAML-style unquoted keys do not pass as JSON.

Tool-backed files can return `syntax.unverified` when the parser is unavailable. Set `strict_toolchains: true` under the syntax gate to make unverified syntax blocking.

## 8. Gate 2: `invariant`

Problem solved: conflict markers, placeholders, and agent edit instructions should not become source.

```java
// ... existing code unchanged ...
public String greet(String name) {
    return "Hello, " + name;
}
```

Expected: hard `invariant.ai-edit-artifact`. Other examples include merge markers, configured TBD/FIXME placeholders, assistant chatter, provenance text pasted into source, and explicit tautology tests.

## 9. Gate 3: `dependency`

Problem solved: an import is present but no local project evidence declares it.

```java
import com.fasterxml.jackson.databind.ObjectMapper;
```

The lab POM has no Jackson dependency. Expected: hard `dependency.unresolved-import`. Add the real dependency to fix it, or add a reviewed internal root to `allow` for private/generated code.

## 10. Gate 4: `package-existence`

Problem solved: a newly declared package name may be hallucinated or misspelled.

Enable it in `.aiv/config.yaml` on the trusted branch, then add:

```text
# requirements.txt
ghost-tensor-utils==1.0
```

Expected with registry access: hard unknown-package finding after a definitive not-found response. Timeout, 5xx, or offline is reported as unverified; `strict: true` makes unverified blocking.

This gate uses an external registry. `aiv verify` reports `package-registry` in `external_inputs`.

## 11. Gate 5: `test-adequacy`

Problem solved: a generated test can execute code without checking a result.

```java
@Test
void greets() {
    new Greeter().greet("Ada");
}
```

Expected: hard `test-adequacy.assertion-free-test`. Restore `assertEquals` to fix it.

A production change without a changed or conventionally named test produces advisory `test-adequacy.missing-tests`. Assertion presence is lexical evidence, not proof that the assertion is meaningful; execute tests/mutation analysis separately.

## 12. Gate 6: `security`

Problem solved: recognizable credential material appears on an added line.

```java
String accessKey = "AKIAABCDEFGHIJKLMNOP";
```

Expected: hard `security.aws-key`. Other rules cover several provider token formats, private-key headers, assigned secrets, and keyword-bound high-entropy values. Use narrow `allow_patterns` for known fixtures and a full secret scanner through `external` for repository/history coverage.

## 13. Gate 7: `design`

Problem solved: a change violates a repository-owned architecture constraint.

```java
public void shutdown() {
    System.exit(0);
}
```

Expected: hard design finding for `no-process-exit`. The rule comes from the immutable `.aiv/design-rules.yaml` snapshot, not the branch copy.

## 14. Gate 8: `rules`

Problem solved: teams need project/framework checks without creating one Java module per framework.

Create `.aiv/rules/team.yaml` on the trusted branch:

```yaml
rules:
  - id: no-empty-handler
    kind: hollow-function
    paths: ["**/*.java"]
    function: "handle\\w*"
    message: "{value} is an empty handler"
```

Enable the gate:

```yaml
- id: rules
  enabled: true
  config:
    rules_files: [".aiv/rules/*.yaml"]
```

Then add an empty `handleRequest()` method. Expected: `rules.no-empty-handler`. Rule kinds include `pattern`, `call`, and `hollow-function`. Classpath packs such as `airflow` are optional examples.

## 15. Gate 9: `density`

Problem solved: unusually thin or repetitive structure deserves focused review.

```java
public class EmptyService {
    private String a;
    private String b;
    private String c;
}
```

Expected: advisory `density.ldr` or entropy signal under configured thresholds. Keep density advisory because low density can be legitimate in models/configuration classes.

## 16. Gate 10: `cohesion`

Problem solved: one change touches many unrelated repository areas.

Add changed files under four top-level/depth-two areas, such as:

```text
api/orders/A.java
ui/profile/B.java
storage/cache/C.java
ops/deploy/D.java
```

Expected: advisory cohesion finding when the configured area/file threshold is exceeded. Use it as a reviewer-routing signal, not a semantic correctness claim.

## 17. Gate 11: `duplication`

Problem solved: an added code block copies existing repository logic.

Create an existing helper with six or more significant lines, commit it to `main`, then paste the same normalized block into a new changed file. Expected: advisory duplication finding naming the existing file and line.

This is an exact normalized-window signal, not a fuzzy clone detector. It is intentionally advisory.

## 18. Gate 12: `external`

Problem solved: teams already trust tools such as ruff, Semgrep, gitleaks, Checkstyle, or another SARIF producer and need one evidence receipt.

Example Python/ruff configuration:

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
        min_level: warning
        on_missing: skip
```

A ruff finding becomes `external.ruff.<rule>`. Set `on_missing: fail` for a required CI tool. Processes are bounded and killed on timeout.

## 19. Gate 13: `doc-integrity`

Problem solved: documentation links to missing files, fabricated paths, incomplete commands, or violates project doc rules.

Enable the gate and create `.aiv/doc-rules.yaml`:

```yaml
doc_constraints:
  - id: build-command
    trigger_keywords: ["build"]
    required_mentions: ["mvn verify"]
    scope: [README.md]
```

Change README to link to `docs/missing.md`. Expected: `doc-integrity.markdown-link` or file-existence finding. Documentation targets are checked in the source workspace; doc policy is read from the frozen policy workspace.

## 20. Gate 14: `pr-metadata`

Problem solved: a pull request arrives without enough context for review.

Enable it:

```yaml
- id: pr-metadata
  enabled: true
  config:
    min_title_words: 3
    require_disclosure: false
```

Run locally with simulated PR metadata:

```bash
AIV_PR_TITLE="Fix" AIV_PR_BODY="Fix" \
java -jar "$AIV_JAR" --workspace . --diff main --head HEAD
```

Expected: title/body findings. The GitHub Action supplies the real event values. The optional disclosure check tests presence only and never infers authorship.

## 21. Gate 15: `license-header`

Problem solved: a newly added source file lacks an approved project header.

```yaml
- id: license-header
  enabled: true
  config:
    header_markers:
      - "SPDX-License-Identifier: Apache-2.0"
```

Add a source file without the marker. Expected: hard missing-header finding. The gate checks newly added files; whole-tree license analysis remains a separate tool.

## 22. Gate 16: `jev`

Problem solved: some calibrated review questions require judgement rather than deterministic parsing.

```yaml
- id: jev
  enabled: true
  severity: warn
  config:
    base_url: http://localhost:11434
    report_at: 0.75
    checks:
      - id: scope-match
        question: "Does the change exceed the stated scope?"
```

Jev is always advisory and is declared as `decision-model` in `external_inputs`. Missing credentials, service errors, or missing answers become visible skip/no-answer states. It never changes a passing deterministic verdict into a block.

## 23. Inspect JSON and SARIF

```bash
java -jar "$AIV_JAR" --workspace . --diff main --head HEAD \
  --output-json aiv-report.json \
  --output-sarif aiv-results.sarif
```

JSON preserves every gate, `blocks_ci`, notices, and structured findings. SARIF uses `error` for blockers, `warning` for advisory failures, and `note` for pass-with-unverified evidence.

## 24. Prove the policy cannot change itself

On `main`, keep a blocking security/design policy. On a feature branch, disable it and add a matching defect:

```bash
java -jar "$AIV_JAR" --workspace . --diff main --head HEAD --require-trusted-config
```

Expected: strict policy-tree drift refusal. AIV pins `main` once to a commit OID, compares the complete `.aiv` tree including ignored files, rejects symlinks, materializes policy from Git blobs, and makes all policy-bearing gates consume that frozen snapshot.

![AIV immutable policy and evidence flow](images/aiv-trust-evidence.png)

**Figure 2: The artifact and the yardstick remain separate.** The change is untrusted. The policy comes from one pinned commit and is frozen before gate execution. The verdict and audit name the same policy commit.

## 25. Call `aiv verify`

Create `change.json`:

```json
{
  "artifact": {
    "baseRef": "memory",
    "headRef": "proposed",
    "rawDiff": "",
    "files": [
      {"path":"src/main/java/app/Greeter.java","changeType":"MODIFIED","content":"package app; public class Greeter {}"}
    ]
  },
  "policyRef": "main",
  "context": {"workspace":"."}
}
```

Run:

```bash
java -jar "$AIV_JAR" verify --request change.json --workspace . \
  --policy-ref main --require-trusted-policy --audit-log .aiv/audit.log
```

The schema-v2 response includes verdict, findings, exact policy commit, policy digest, evidence digest, declared external inputs, and audit head. Full contract: [MCP.md](MCP.md).

## 26. Verify and report the audit chain

```bash
java -jar "$AIV_JAR" audit verify .aiv/audit.log
java -jar "$AIV_JAR" report --audit-log .aiv/audit.log --out aiv-evidence.html
```

For independent tamper evidence, store the returned chain head outside the log and later pass `--expect-head <hash>`.

## 27. GitHub Action with the source-built jar

The hardened Action requires a compatible jar. Until the next release is published, build the jar in the workflow and pass `cli-jar-path`:

```yaml
name: AIV
on: [pull_request]
permissions:
  contents: read
  checks: write
  pull-requests: write
jobs:
  aiv:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5
        with: { fetch-depth: 0 }
      - uses: actions/setup-java@c1e323688fd81a25caa38c78aa6df2d33d3e20d9
        with: { distribution: temurin, java-version: 17 }
      - run: mvn -B -ntp -DskipTests -Djacoco.skip=true -pl aiv-cli -am package
      - uses: vaquarkhan/aiv-integrity-gate@main
        with:
          base-ref: origin/${{ github.base_ref }}
          cli-jar-path: aiv-cli/target/aiv-cli-1.0.4.jar
          publish-github-checks: "true"
```

For a consumer repository, use a pinned compatible release jar URL/path rather than cloning mutable source in every run.

## 28. AI-DLC sensor

Advisory file-local feedback:

```bash
python "$AIV_SOURCE/integrations/aidlc/aiv_aidlc_sensor.py" \
  --aiv-jar "$AIV_JAR" --file src/main/java/app/Greeter.java
```

Authoritative phase evidence uses a full request:

```bash
python "$AIV_SOURCE/integrations/aidlc/aiv_aidlc_sensor.py" \
  --aiv-jar "$AIV_JAR" --request change.json --workspace . \
  --policy-ref main --blocking
```

The complete plugin lives under `integrations/aidlc/plugin/`. See [AIDLC.md](AIDLC.md).

## 29. Choose what to enable

Do not enable everything just because it exists. Start with the problem you need to solve:

- smallest local filter: `minimal`,
- balanced Java repository: `java-ci`,
- broad generated-change integrity: `agent-paste-strict`,
- framework pack example: `airflow`,
- community/advisory example: `asf`,
- custom explicit profile: recommended for production.

See [MODULE-ADOPTION-GUIDE.md](MODULE-ADOPTION-GUIDE.md) for mandatory modules, optional gates, external prerequisites, and exact profile matrices.

## 30. Clean up the lab

```bash
cd ..
rm -rf hello-aiv
```

Keep the source checkout if you are developing AIV. Run `mvn clean` inside it to remove build artifacts.

**Author:** Viquar Khan
