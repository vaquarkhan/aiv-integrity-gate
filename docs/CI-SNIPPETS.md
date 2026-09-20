# CI snippets (beyond GitHub Actions)

The CLI is portable: any runner with **JDK 17+** and **Git** can run the shaded JAR.
GitHub composite action: root [`action.yml`](../action.yml).

## Generic (any CI)

```bash
curl -fsSL -o aiv-cli.jar \
  "https://repo1.maven.org/maven2/io/github/vaquarkhan/aiv/aiv-cli/1.0.4/aiv-cli-1.0.4.jar"
java -jar aiv-cli.jar --workspace . --diff origin/main --head HEAD
```

Docker:

```bash
docker build -t aiv-gate:local .
docker run --rm -v "$PWD:/workspace" -w /workspace aiv-gate:local --diff origin/main
```

## GitLab CI

```yaml
aiv:
  image: eclipse-temurin:17-jdk
  stage: test
  variables:
    AIV_VERSION: "1.0.4"
  script:
    - apt-get update && apt-get install -y git curl
    - curl -fsSL -o aiv-cli.jar
        "https://repo1.maven.org/maven2/io/github/vaquarkhan/aiv/aiv-cli/${AIV_VERSION}/aiv-cli-${AIV_VERSION}.jar"
    - java -jar aiv-cli.jar --workspace . --diff "origin/${CI_MERGE_REQUEST_TARGET_BRANCH_NAME:-main}" --head HEAD
  rules:
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
```

## Azure Pipelines

```yaml
jobs:
  - job: aiv
    pool:
      vmImage: ubuntu-latest
    steps:
      - checkout: self
        fetchDepth: 0
      - task: JavaToolInstaller@0
        inputs:
          versionSpec: "17"
          jdkArchitectureOption: x64
          jdkSourceOption: PreInstalled
      - bash: |
          curl -fsSL -o aiv-cli.jar \
            "https://repo1.maven.org/maven2/io/github/vaquarkhan/aiv/aiv-cli/1.0.4/aiv-cli-1.0.4.jar"
          java -jar aiv-cli.jar --workspace . --diff origin/main --head HEAD
        displayName: Run AIV Integrity Gate
```

## pre-push hook (local)

```bash
#!/usr/bin/env bash
# .git/hooks/pre-push
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
JAR="${AIV_CLI_JAR:-$HOME/.aiv/aiv-cli-1.0.4.jar}"
if [[ ! -f "$JAR" ]]; then
  mkdir -p "$(dirname "$JAR")"
  curl -fsSL -o "$JAR" \
    "https://repo1.maven.org/maven2/io/github/vaquarkhan/aiv/aiv-cli/1.0.4/aiv-cli-1.0.4.jar"
fi
java -jar "$JAR" --workspace "$ROOT" --diff "@{upstream}" --head HEAD 2>/dev/null \
  || java -jar "$JAR" --workspace "$ROOT" --diff origin/main --head HEAD
```

Prefer [PRE-COMMIT.md](PRE-COMMIT.md) for staged-tree checks at commit time.
