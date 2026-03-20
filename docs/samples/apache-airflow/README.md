# Apache Airflow AIV Sample

Sample AIV configuration for Apache Airflow. Airflow is a Python project; AIV checks Python files against design rules and dependencies. Adapt the rules for your own Python project.

**Author:** Vaquar Khan

---

## What This Sample Covers

| Category | Rules |
|----------|-------|
| DAG context | Use `with DAG(...) as dag` pattern |
| Deprecated | days_ago, airflow.utils.dates |
| Security | No eval or exec in DAG files |
| Providers | Import from airflow.providers |
| Logging | Use airflow.utils.log |

---

## Setup

```bash
mkdir -p .aiv
cp docs/samples/apache-airflow/config.yaml .aiv/
cp docs/samples/apache-airflow/design-rules.yaml .aiv/
cp docs/samples/apache-airflow/doc-rules.yaml .aiv/
```

Add `.github/workflows/aiv.yml` per [DEVELOPER-CONFIGURATION.md](../../DEVELOPER-CONFIGURATION.md). Airflow uses Python; ensure the workflow installs Java 17 for AIV CLI.

---

## Python and AIV

AIV density gate runs entropy checks on Python files. Design gate applies YAML rules to `.py` files. Dependency gate validates imports against `requirements.txt` or `pyproject.toml`.

---

## See Also

- [TUTORIAL-APACHE-AIRFLOW.md](../../TUTORIAL-APACHE-AIRFLOW.md) — Full Airflow walkthrough
- [Airflow Contributing](https://github.com/apache/airflow/blob/main/CONTRIBUTING.rst)
- [Airflow Docs](https://airflow.apache.org/)
