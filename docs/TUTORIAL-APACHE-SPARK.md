# AIV Tutorial: Apache Spark End-to-End Demo

A step-by-step guide showing how to add AIV to Apache Spark, with a concrete Jira/SPIP example and end-to-end validation.

**Author:** Vaquar Khan

---

## Part 1: Scenario Overview

### The Example: SPARK-12345

Assume a contributor proposes a new feature via:

- **Jira:** [SPARK-12345](https://issues.apache.org/jira/browse/SPARK-12345) — Add configurable shuffle partition limit
- **SPIP:** Required for user-facing config changes
- **Design doc:** `docs/design/spark-12345-shuffle-partition-limit.md`

The SPIP states:

- Use `SparkConf` for configuration, not system properties
- Do not add new public APIs without SPIP approval
- Follow existing patterns in `SparkContext`

### What AIV Adds

AIV enforces these design constraints before the PR reaches reviewers. It catches:

- Code that uses forbidden patterns (e.g. `System.getProperty` for config)
- Code that misses required patterns (e.g. `SparkConf`)
- Low logic density (boilerplate-heavy implementations)

---

## Part 2: Adding AIV to Apache Spark

### Step 1: Fork and clone Spark

```bash
git clone https://github.com/apache/spark.git
cd spark
```

### Step 2: Create AIV config directory

```bash
mkdir -p .aiv
```

### Step 3: Add config.yaml

Create `.aiv/config.yaml`:

```yaml
gates:
  - id: density
    enabled: true
    config:
      ldr_threshold: 0.22
      entropy_threshold: 3.6

  - id: design
    enabled: true
    config:
      rules_path: .aiv/design-rules.yaml

  - id: invariant
    enabled: true
```

Spark has a large codebase; slightly lower thresholds reduce false positives while still catching obvious issues.

### Step 4: Add design-rules.yaml from SPIP

Create `.aiv/design-rules.yaml` based on SPIP and Spark conventions:

```yaml
# Design rules derived from SPIP and Spark conventions
# Jira: SPARK-12345, SPIP: Shuffle Partition Limit

constraints:
  # Config must use SparkConf, not system properties
  - id: config-sparkconf
    keywords: [SparkConf, spark.conf, getConf]
    forbidden_calls:
      - System.getProperty
      - System.setProperty
    required_calls: []

  # New config keys must go through SparkConf
  - id: config-keys
    keywords: [shuffle.partitions, spark.sql.shuffle.partitions]
    forbidden_calls:
      - Runtime.getRuntime
    required_calls: []

  # New config code must not use system properties
  - id: no-sysprops
    keywords: [spark., shuffle, partition]
    forbidden_calls:
      - System.getProperty
      - System.setProperty
    required_calls: []
```

### Step 5: Add AIV to CI

Create or edit `.github/workflows/aiv.yml`:

```yaml
name: AIV Gate

on:
  pull_request:
    branches: [master, main]

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

      - name: Build AIV CLI
        run: |
          git clone https://github.com/apache/aiv-gate.git /tmp/aiv-gate
          cd /tmp/aiv-gate && mvn clean package -DskipTests -q

      - name: Run AIV
        run: |
          java -jar /tmp/aiv-gate/aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar \
            --workspace ${{ github.workspace }} \
            --diff origin/${{ github.base_ref }}
```

Or use the infrastructure-actions connector when available:

```yaml
jobs:
  aiv:
    uses: apache/infrastructure-actions/.github/workflows/aiv.yml@main
```

### Step 6: Commit and push

```bash
git add .aiv/ .github/workflows/aiv.yml
git commit -m "[SPARK-12345] Add AIV gate for design compliance"
git push origin your-branch
```

---

## Part 3: End-to-End Demo

### Scenario: Contributor submits a PR

A contributor implements SPARK-12345 and opens a PR. Their code:

```java
// In SparkContext.java or similar
public int getShufflePartitionLimit() {
    String value = System.getProperty("spark.shuffle.partitionLimit");
    if (value != null) {
        return Integer.parseInt(value);
    }
    return conf.get("spark.sql.shuffle.partitions", "200");
}
```

### What AIV catches

1. **Design gate** — `System.getProperty` is forbidden by `config-sparkconf`. The constraint applies because the file contains `SparkConf` / `conf`.
2. **Report** — AIV fails with: `Forbidden call 'System.getProperty' in ... (constraint: config-sparkconf)`

### Corrected implementation

The contributor fixes the code:

```java
public int getShufflePartitionLimit() {
    return conf.getInt("spark.sql.shuffle.partitions", 200);
}
```

### What AIV does now

1. **Density gate** — Logic is concise; LDR and entropy pass.
2. **Design gate** — No forbidden calls; uses `SparkConf` correctly.
3. **Invariant gate** — Passes.
4. **Result** — `Overall: PASS`, exit code 0.

---

## Part 4: Benefits for Apache Spark

| Benefit | Description |
|---------|-------------|
| **SPIP alignment** | Design rules encode SPIP constraints; PRs are checked before review |
| **Jira traceability** | Rules reference SPARK-XXXX; design-rules.yaml documents the link |
| **Reviewer time** | Low-quality or non-compliant PRs fail early; reviewers focus on good PRs |
| **Consistency** | All contributors get the same constraints; no manual checklist drift |
| **Onboarding** | New contributors see immediate feedback instead of waiting for review comments |

### Example: Before vs. after AIV

| Metric | Before AIV | After AIV |
|--------|------------|-----------|
| PRs needing "use SparkConf" fix | ~10/month | Caught by CI |
| Time to first feedback | 2–5 days | Minutes |
| Review cycles for design violations | 2–3 | 0 (fixed before review) |

---

## Part 5: Linking Jira and SPIP to AIV

### Design doc structure

Spark can store design docs in `docs/` or `design/`. For AIV:

1. **SPIP doc** — In Jira or `docs/design/` (e.g. `spark-12345-shuffle-partition-limit.md`)
2. **Design rules** — `.aiv/design-rules.yaml` encodes constraints from the SPIP

### Example: SPIP to design-rules mapping

| SPIP requirement | design-rules.yaml constraint |
|------------------|------------------------------|
| "Use SparkConf for config" | `forbidden_calls: [System.getProperty]` |
| "No new public APIs without approval" | `keywords: [public]` + project-specific rules |
| "Follow SparkContext patterns" | `required_calls: [conf.get]` when touching config |

### Comment in design-rules.yaml

```yaml
# SPARK-12345: Add configurable shuffle partition limit
# SPIP: https://issues.apache.org/jira/browse/SPARK-12345
# Design: docs/design/spark-12345-shuffle-partition-limit.md
```

---

## Part 6: Testing the Setup

### 6.1 Run AIV locally

```bash
cd spark
java -jar /path/to/aiv-cli.jar --diff origin/master
```

### 6.2 Test with a violating change

Add a temporary file:

```java
// test-violation.java
public class Test {
    public String getConfig() {
        return System.getProperty("spark.test");
    }
}
```

Run AIV. It should fail with a design violation.

### 6.3 Test with a compliant change

```java
// test-ok.java
public class Test {
    public int getLimit(SparkConf conf) {
        return conf.getInt("spark.limit", 100);
    }
}
```

Run AIV. It should pass.

### 6.4 Verify in CI

1. Push a branch with the AIV workflow
2. Open a PR
3. Confirm the AIV job runs and reports correctly

---

## Part 7: Rollout Strategy

### Phase 1: Shadow mode (optional)

Run AIV but do not block the build. Post results as a PR comment only:

```yaml
- name: Run AIV (shadow)
  run: |
    java -jar aiv-cli.jar --diff origin/${{ github.base_ref }} || true
```

### Phase 2: Advisory

Run AIV and post results. Fail the job only on critical violations (e.g. density < 0.15).

### Phase 3: Gating

Run AIV and fail the build on any gate failure. Add to branch protection rules.

---

## Part 8: Quick Reference

### Files to add for Spark

```
spark/
├── .aiv/
│   ├── config.yaml
│   └── design-rules.yaml
└── .github/
    └── workflows/
        └── aiv.yml
```

### Minimum config

```yaml
# .aiv/config.yaml
gates:
  - id: density
    enabled: true
  - id: design
    enabled: true
    config:
      rules_path: .aiv/design-rules.yaml
  - id: invariant
    enabled: true
```

### Jira/SPIP workflow

1. Create Jira ticket (e.g. SPARK-12345)
2. Create SPIP if required
3. Add design-rules.yaml constraints from SPIP
4. Implement and open PR
5. AIV runs automatically; fix violations before review

---

## Sample Rules File

A full sample rules file for Spark is at [samples/apache-spark-design-rules.yaml](samples/apache-spark-design-rules.yaml). Copy it to `.aiv/design-rules.yaml` in your Spark fork and adjust as needed.

## See Also

- [TUTORIAL.md](TUTORIAL.md) — General AIV tutorial
- [DEVELOPER-CONFIGURATION.md](DEVELOPER-CONFIGURATION.md) — Full config reference
- [Spark SPIP](https://spark.apache.org/improvement-proposals.html)
- [Spark Jira](https://issues.apache.org/jira/projects/SPARK)
