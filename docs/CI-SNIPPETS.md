# CI snippets beyond GitHub Actions

Any CI runner with JDK 17+ and complete Git base history can run AIV 2.0. Point `AIV_CLI_URL` at `aiv-cli-2.0.jar` from tag `v2.0` or from Maven Central once that coordinate is uploaded.

## Generic strict CI

```bash
curl -fsSL -o aiv-cli.jar "$AIV_CLI_URL"
java -jar aiv-cli.jar verify --capabilities
java -jar aiv-cli.jar --workspace . --diff "$BASE_REF" --head "$HEAD_REF" \
  --require-trusted-config --output-json aiv-report.json --output-sarif aiv-results.sarif
```

## GitLab CI

```yaml
aiv:
  image: eclipse-temurin:17-jdk
  stage: test
  variables:
    AIV_CLI_URL: "https://artifacts.example/aiv/aiv-cli-compatible.jar"
  before_script:
    - apt-get update && apt-get install -y git curl
    - git fetch origin "$CI_MERGE_REQUEST_TARGET_BRANCH_NAME"
  script:
    - curl -fsSL -o aiv-cli.jar "$AIV_CLI_URL"
    - java -jar aiv-cli.jar verify --capabilities
    - java -jar aiv-cli.jar --workspace .
        --diff "origin/$CI_MERGE_REQUEST_TARGET_BRANCH_NAME" --head "$CI_COMMIT_SHA"
        --require-trusted-config --output-json aiv-report.json --output-sarif aiv-results.sarif
  artifacts:
    when: always
    paths: [aiv-report.json, aiv-results.sarif]
  rules:
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
```

## Azure Pipelines

```yaml
jobs:
  - job: aiv
    pool: { vmImage: ubuntu-latest }
    steps:
      - checkout: self
        fetchDepth: 0
      - task: JavaToolInstaller@0
        inputs:
          versionSpec: "17"
          jdkArchitectureOption: x64
          jdkSourceOption: PreInstalled
      - bash: |
          curl -fsSL -o aiv-cli.jar "$AIV_CLI_URL"
          java -jar aiv-cli.jar verify --capabilities
          java -jar aiv-cli.jar --workspace . --diff origin/main --head HEAD \
            --require-trusted-config --output-json aiv-report.json --output-sarif aiv-results.sarif
        displayName: Run AIV Integrity Gate
        env:
          AIV_CLI_URL: $(AIV_CLI_URL)
      - publish: aiv-report.json
        artifact: aiv-json
      - publish: aiv-results.sarif
        artifact: aiv-sarif
```

## Container

```bash
docker build -t aiv-gate:local .
docker run --rm -v "$PWD:/workspace" -w /workspace aiv-gate:local \
  --diff origin/main --head HEAD --require-trusted-config
```

Use an image built from the same reviewed source/release as the policy contract.

## Pre-push

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
JAR="${AIV_CLI_JAR:?set AIV_CLI_JAR to a compatible jar}"
BASE="$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null || echo origin/main)"
java -jar "$JAR" --workspace "$ROOT" --diff "$BASE" --head HEAD
```

Local pre-push is feedback; strict CI remains the enforcement boundary. See [DEPLOYMENT.md](DEPLOYMENT.md).

**Author:** Viquar Khan
