# AIV Example Project

Example project for validating AIV (Automated Integrity Validation) gate.

## Run AIV locally

From the parent `aiv-gate` repo root:

```bash
# Windows
scripts\validate-example.bat

# Linux/Mac
./scripts/validate-example.sh
```

Or manually:

```bash
mvn package -DskipTests -B -q -pl aiv-cli -am
cd aiv-cli/target
java -jar aiv-cli-1.0.0-SNAPSHOT.jar --workspace /path/to/aiv-gate --diff origin/main
```

## Deploy to GitHub

1. Copy this folder to a new repo or push as-is.
2. The `.github/workflows/aiv.yml` runs AIV on every pull request.
3. Ensure `.aiv/config.yaml` and `.aiv/design-rules.yaml` are in the repo root.

## Run tests

```bash
mvn test
```
