# AIV Deployment Guide

This guide covers production deployment patterns for AIV: enabling the gate in a repository, publishing release artifacts, and operating the workflow in CI.

**Author:** Vaquar Khan

---

## Deployment Model

AIV is a CI-native gate. It does not require a persistent application server or hosted control plane.

| Scope | Outcome | Owner |
|------|---------|-------|
| Enable AIV in a repository | Pull requests are validated by AIV in CI | Any consuming team |
| Publish AIV CLI artifacts | Versioned JARs available through releases/Central | AIV maintainers |

On each CI run, source is checked out onto an ephemeral runner, AIV executes against the diff, and results are reported back to the pull request.

---

## Enable AIV in a Repository

Add three files in the target repository:

- `.aiv/config.yaml`
- `.aiv/design-rules.yaml`
- `.github/workflows/aiv.yml`

### Baseline `config.yaml`

```yaml
gates:
  - id: density
    enabled: true
    config:
      ldr_threshold: 0.25
      entropy_threshold: 3.8
  - id: design
    enabled: true
    config:
      rules_path: .aiv/design-rules.yaml
  - id: dependency
    enabled: true
  - id: invariant
    enabled: true
  - id: doc-integrity
    enabled: false
    config:
      rules_path: .aiv/doc-rules.yaml
      auto: true
```

### Baseline `design-rules.yaml`

```yaml
constraints:
  - id: no-system-exit
    keywords: []
    forbidden_calls:
      - System.exit
    required_calls: []

  - id: no-serialization
    keywords: []
    forbidden_calls:
      - implements Serializable
    required_calls: []
```

### GitHub Actions Workflow (`.github/workflows/aiv.yml`)

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
          mvn -B -ntp clean verify -pl aiv-cli -am

      - name: Run AIV
        run: |
          java -jar aiv-src/aiv-cli/target/aiv-cli-1.0.0-SNAPSHOT.jar \
            --workspace ${{ github.workspace }} \
            --diff origin/${{ github.base_ref }}
```

Use `--include-doc-checks` when documentation integrity should run on every pull request regardless of YAML gate toggles.

---

## Repository Validation

After committing the AIV files, open a pull request and confirm:

- the `AIV Gate` job starts automatically
- logs contain gate-by-gate output
- non-compliant changes fail with exit code `1`
- remediated changes pass with exit code `0`

---

## Publishing the AIV CLI (Maintainers)

### Option A: GitHub Releases

This repository includes [`.github/workflows/release-github.yml`](../.github/workflows/release-github.yml). Publishing flow:

```bash
mvn clean verify -pl aiv-cli -am
git tag v1.0.0
git push origin v1.0.0
```

The workflow builds, validates, and attaches `aiv-cli` artifacts to the tagged release.

Release asset URL pattern:

```text
https://github.com/vaquarkhan/aiv-integrity-gate/releases/download/vVERSION/aiv-cli-VERSION.jar
```

### Option B: Maven Central

Central publication is implemented by the `central-publish` profile and [`.github/workflows/publish-maven-central.yml`](../.github/workflows/publish-maven-central.yml).

Required repository secrets:

| Secret | Purpose |
|--------|---------|
| `CENTRAL_TOKEN_USERNAME` | Sonatype token username |
| `CENTRAL_TOKEN_PASSWORD` | Sonatype token password |
| `GPG_PRIVATE_KEY` | ASCII-armored private key |
| `GPG_PASSPHRASE` | Signing key passphrase |

Dry-run validation:

```bash
mvn clean verify -Pcentral-publish -Dgpg.skip=true
```

Release deploy (non-SNAPSHOT versions only):

```bash
mvn clean deploy -Pcentral-publish
```

Operational references:

- [Publish to Central](https://central.sonatype.org/publish/publish-guide/)
- [Maven Central Portal workflow](https://central.sonatype.org/publish/publish-portal-maven/)

---

## Consuming a Published CLI Artifact

When a release artifact is available, use a direct download step in CI:

```yaml
      - name: Download AIV CLI
        run: |
          curl -sL -o aiv-cli.jar \
            "https://github.com/vaquarkhan/aiv-integrity-gate/releases/download/v1.0.0/aiv-cli-1.0.0.jar"

      - name: Run AIV
        run: |
          java -jar aiv-cli.jar --workspace . --diff origin/${{ github.base_ref }}
```

---

## Troubleshooting

| Symptom | Verification |
|---------|--------------|
| AIV workflow not triggered | Confirm file path is `.github/workflows/aiv.yml` and trigger includes `pull_request` |
| Missing config | Confirm `.aiv/config.yaml` exists at repository root |
| JAR execution failure | Build with `mvn clean package` and use the JAR from `aiv-cli/target/` |
| Rule set appears ignored | Confirm `rules_path` and rule keywords match affected files |

---

## Related Documentation

- [README.md](../README.md) — product overview and quick start
- [DEVELOPER-CONFIGURATION.md](DEVELOPER-CONFIGURATION.md) — full configuration reference
