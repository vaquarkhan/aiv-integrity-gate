# AIV Tutorial: Apache Airflow End-to-End Demo

Step-by-step guide for adding AIV to Apache Airflow. Airflow is a Python project. AIV checks Python files for density, design rules, and dependency imports.

**Author:** Vaquar Khan

---

## Part 1: Why Airflow Needs AIV

Airflow contributions involve:

- DAG definitions (Python)
- Operators and sensors
- Providers (airflow.providers)
- Deprecated patterns (days_ago, airflow.contrib)

AIV catches deprecated usage, security issues (eval/exec), and wrong imports before human review.

---

## Part 2: Adding AIV to Airflow

### Step 1: Fork and clone Airflow

```bash
git clone https://github.com/apache/airflow.git
cd airflow
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
      ldr_threshold: 0.25
      entropy_threshold: 3.8
      file_extensions: [".py"]
  - id: design
    enabled: true
    config:
      rules_path: .aiv/design-rules.yaml
      file_extensions: [".py"]
  - id: dependency
    enabled: true
  - id: invariant
    enabled: true
```

Density gate runs entropy checks on Python (LDR is Java-only). Design and dependency apply to `.py` files.

### Step 4: Add design-rules.yaml from sample

From the aiv-integrity-gate repo (clone it if needed):

```bash
git clone https://github.com/vaquarkhan/aiv-integrity-gate.git /tmp/aiv-gate
cp /tmp/aiv-gate/docs/samples/apache-airflow/config.yaml .aiv/
cp /tmp/aiv-gate/docs/samples/apache-airflow/design-rules.yaml .aiv/
```

The sample includes rules for:

- No days_ago (deprecated)
- No eval/exec in DAG code
- No sys.exit
- Use airflow.providers, not airflow.contrib

### Step 5: Add AIV to CI

Airflow CI uses Python. AIV CLI is Java. Add a job that installs Java and runs AIV:

```yaml
name: AIV Gate

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

      - name: Clone and build AIV
        run: |
          git clone https://github.com/vaquarkhan/aiv-integrity-gate.git aiv-src
          cd aiv-src
          mvn clean package -DskipTests -B -q

      - name: Run AIV
        run: |
          java -jar aiv-src/aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar \
            --workspace ${{ github.workspace }} \
            --diff origin/${{ github.base_ref }}
```

### Step 6: Commit and push

```bash
git add .aiv/ .github/workflows/aiv.yml
git commit -m "Add AIV gate for Airflow"
git push origin your-branch
```

---

## Part 3: Example: Deprecated days_ago

### Wrong code (AIV fails)

```python
from airflow.utils.dates import days_ago

with DAG(
    dag_id="example",
    start_date=days_ago(1),  # Deprecated
    schedule=None,
) as dag:
    pass
```

AIV design gate fails: `days_ago` is deprecated. Use `datetime.timedelta` or `pendulum`.

### Correct code (AIV passes)

```python
from datetime import datetime, timedelta

with DAG(
    dag_id="example",
    start_date=datetime.now() - timedelta(days=1),
    schedule=None,
) as dag:
    pass
```

---

## Part 4: Example: No eval/exec

### Wrong code (AIV fails)

```python
task_id = eval(user_input)  # Dangerous
```

AIV design gate fails: `eval` is forbidden in DAG code.

---

## Part 5: Dependency Gate for Python

AIV dependency gate validates Python imports against `requirements.txt` or `pyproject.toml`. Unknown imports fail. Add new packages to the lockfile before importing.

---

## Part 6: Testing the Setup

### Run AIV locally

```bash
cd airflow
java -jar /path/to/aiv-cli.jar --diff origin/main
```

### Verify in CI

1. Push a branch with the AIV workflow
2. Open a PR
3. Confirm the AIV job runs and reports correctly

---

## Part 7: Quick Reference

### Files to add for Airflow

```
airflow/
├── .aiv/
│   ├── config.yaml
│   └── design-rules.yaml
└── .github/
    └── workflows/
        └── aiv.yml
```

### Sample location

Full sample at [docs/samples/apache-airflow/](../samples/apache-airflow/).

---

## See Also

- [samples/apache-airflow/README.md](samples/apache-airflow/README.md) — Airflow sample overview
- [TUTORIAL.md](TUTORIAL.md) — General AIV tutorial
- [Airflow Contributing](https://github.com/apache/airflow/blob/main/CONTRIBUTING.rst)
