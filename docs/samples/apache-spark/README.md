# Apache Spark AIV Sample

Sample AIV configuration for Apache Spark. Based on Spark contributing guide, SPIP process, and configuration docs. Adapt the rules for your own project.

**Author:** Vaquar Khan

---

## What This Sample Covers

| Category | Rules |
|----------|-------|
| Configuration | Use SparkConf, not System.getProperty |
| Deprecated | DStream, StreamingContext, HiveContext, UserDefinedAggregateFunction |
| Serialization | No java.io.Serializable (prefer Kryo) |
| Terminology | No blacklist/blackList (use exclude) |
| Logging | No System.out.println in production |

---

## Setup

```bash
mkdir -p .aiv
cp docs/samples/apache-spark/config.yaml .aiv/
cp docs/samples/apache-spark/design-rules.yaml .aiv/
cp docs/samples/apache-spark/doc-rules.yaml .aiv/
```

Enable doc-integrity in config with `enabled: true` and `auto: true`, or use `--include-doc-checks` when running AIV.

Add `.github/workflows/aiv.yml` per [DEVELOPER-CONFIGURATION.md](../../DEVELOPER-CONFIGURATION.md).

---

## Jira and SPIP

Spark uses Jira (SPARK-XXXX) and SPIP for design proposals. Add design-rules constraints from your SPIP. Example:

```yaml
# SPARK-12345: Add configurable shuffle partition limit
# SPIP: https://issues.apache.org/jira/browse/SPARK-12345
```

---

## See Also

- [TUTORIAL-APACHE-SPARK.md](../../TUTORIAL-APACHE-SPARK.md) — Full Spark walkthrough
- [Spark Contributing](https://spark.apache.org/contributing.html)
- [Spark Jira](https://issues.apache.org/jira/projects/SPARK)
