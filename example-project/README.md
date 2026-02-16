# AIV Example Project

Example project for validating AIV (Automated Integrity Validation) gate.

## Run AIV locally

From the parent `aiv-gate` repo:

```bash
mvn clean package -DskipTests
java -jar aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar --workspace example-project --diff origin/main
```

## Run tests

```bash
mvn test
```
