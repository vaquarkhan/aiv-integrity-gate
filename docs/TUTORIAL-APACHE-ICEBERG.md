# AIV Tutorial: Apache Iceberg End-to-End Demo

Step-by-step guide for adding AIV to Apache Iceberg. Iceberg has strict architecture rules around snapshot expiration, schema evolution, and metadata. AIV catches violations before human review.

**Author:** Vaquar Khan

---

## Part 1: Why Iceberg Needs AIV

Iceberg contributions often involve:

- Snapshot expiration (must use ExpireSnapshots API, not in-memory lists)
- Schema evolution (must use updateSchema, Types API, Expressions)
- Partition and sort order (immutable; use updateSpec, replaceSortOrder)
- Transaction patterns (must call .commit())

Contributors sometimes use wrong APIs that compile but do not work. AIV design rules catch these before review.

---

## Part 2: Adding AIV to Iceberg

### Step 1: Fork and clone Iceberg

```bash
git clone https://github.com/apache/iceberg.git
cd iceberg
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
      refactor_net_loc_threshold: -50
      trusted_authors: []
  - id: design
    enabled: true
    config:
      rules_path: .aiv/design-rules.yaml
  - id: dependency
    enabled: true
  - id: invariant
    enabled: true
```

### Step 4: Add design-rules.yaml from sample

From the aiv-integrity-gate repo (clone it if needed):

```bash
git clone https://github.com/vaquarkhan/aiv-integrity-gate.git /tmp/aiv-gate
cp /tmp/aiv-gate/docs/samples/apache-iceberg/config.yaml .aiv/
cp /tmp/aiv-gate/docs/samples/apache-iceberg/design-rules.yaml .aiv/
```

Or create `.aiv/design-rules.yaml` manually from the sample content. The sample includes rules for:

- Snapshot expiration (ExpireSnapshots API)
- Schema evolution (updateSchema, Types, Expressions)
- Deprecated APIs (MetadataUpdate.RemoveSnapshot, FileRewriter)
- Transaction patterns (.commit)
- Standards (Apache license, no Thread.sleep in tests)

### Step 5: Add AIV to CI

Create `.github/workflows/aiv.yml`:

```yaml
name: AIV Gate

on:
  pull_request:
    branches: [main, master]

jobs:
  aiv:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5
        with:
          fetch-depth: 0

      - uses: actions/setup-java@c1e323688fd81a25caa38c78aa6df2d33d3e20d9
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
git commit -m "Add AIV gate for Iceberg"
git push origin your-branch
```

---

## Part 3: Example: Snapshot Expiration

### Wrong code (AIV fails)

```java
public void expireSnapshotsByDuration(long duration) {
    List<Snapshot> snapshots = table.snapshots();
    List<Snapshot> toRemove = new ArrayList<>();
    for (Snapshot s : snapshots) {
        if (System.currentTimeMillis() - s.timestampMillis() > duration) {
            toRemove.add(s);
        }
    }
    table.removeSnapshots(toRemove);  // Wrong: does not exist; must use ExpireSnapshots
}
```

AIV design gate fails: `table.removeSnapshots` is forbidden. Snapshot expiration must use the ExpireSnapshots API to rewrite manifest lists.

### Correct code (AIV passes)

```java
public void expireSnapshotsByDuration(long duration) {
    table.expireSnapshots()
        .expireOlderThan(System.currentTimeMillis() - duration)
        .commit();
}
```

---

## Part 4: Example: Schema Evolution

### Wrong code (AIV fails)

```java
schema.addColumn("col", Type.LongType.get());  // Wrong: schema is immutable
```

AIV design gate fails: `schema.add` is forbidden. Use `table.updateSchema().addColumn(...).commit()`.

### Correct code (AIV passes)

```java
table.updateSchema()
    .addColumn("col", Types.LongType.get())
    .commit();
```

---

## Part 5: Testing the Setup

### Run AIV locally

```bash
cd iceberg
java -jar /path/to/aiv-cli.jar --diff origin/main
```

### Test with a violating change

Add a Java file that calls `table.removeSnapshots`. Run AIV. It should fail with a design violation.

### Verify in CI

1. Push a branch with the AIV workflow
2. Open a PR
3. Confirm the AIV job runs and reports correctly

---

## Part 6: Quick Reference

### Files to add for Iceberg

```
iceberg/
├── .aiv/
│   ├── config.yaml
│   └── design-rules.yaml
└── .github/
    └── workflows/
        └── aiv.yml
```

### Sample location

Full sample at [docs/samples/apache-iceberg/](../samples/apache-iceberg/).

---

## See Also

- [samples/apache-iceberg/README.md](samples/apache-iceberg/README.md) — Iceberg sample overview
- [TUTORIAL.md](TUTORIAL.md) — General AIV tutorial
- [Iceberg Contributing](https://iceberg.apache.org/contribute/)
- [Iceberg Maintenance](https://iceberg.apache.org/docs/latest/maintenance/)
