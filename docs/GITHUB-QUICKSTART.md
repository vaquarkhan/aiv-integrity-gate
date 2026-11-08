# Five-minute GitHub setup

This guide lets a repository run the released AIV CLI without building Maven or copying AIV source. It assumes a hardened AIV release has been published to Maven Central and a full immutable Action commit SHA is available.

## Before you begin

- Your repository uses GitHub Actions.
- You can create `.aiv/config.yaml` and a workflow file.
- The workflow can check out full Git history.
- You have the released AIV version and immutable Action commit SHA from the release notes.

## 1. Create an explicit policy

Run the released CLI once locally or add this starter policy directly:

```yaml
# .aiv/config.yaml
schema_version: 1
fail_fast: false

gates:
  - { id: syntax, enabled: true }
  - { id: invariant, enabled: true }
  - { id: dependency, enabled: true }
  - { id: package-existence, enabled: false }
  - { id: test-adequacy, enabled: true }
  - { id: security, enabled: false }
  - { id: design, enabled: false }
  - { id: density, enabled: true, severity: warn }
  - { id: cohesion, enabled: true, severity: warn }
  - { id: duplication, enabled: true, severity: warn }
  - { id: doc-integrity, enabled: false }
  - { id: pr-metadata, enabled: false }
  - { id: license-header, enabled: false }
  - { id: external, enabled: false }
  - { id: rules, enabled: false }
  - { id: jev, enabled: false }
  - { id: review-routing, enabled: false }
  - { id: change-policy, enabled: false }
```

Commit the policy to the base branch. Strict AIV runs read policy from that protected base commit, not from a proposed pull request.

## 2. Add the workflow

Create `.github/workflows/aiv.yml`. Replace both placeholder values with the released CLI version and its full immutable Action commit SHA.

```yaml
name: AIV

on:
  pull_request:

permissions:
  contents: read
  checks: write
  security-events: write

jobs:
  aiv:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5
        with:
          fetch-depth: 0

      - name: Run AIV
        id: aiv
        continue-on-error: true
        uses: vaquarkhan/aiv-integrity-gate@<IMMUTABLE_ACTION_COMMIT_SHA>
        with:
          base-ref: origin/${{ github.base_ref }}
          aiv-version: "<RELEASED_VERSION>"
          output-json: aiv-report.json
          output-sarif: aiv-results.sarif
          publish-github-checks: "true"

      - name: Upload SARIF
        if: always() && steps.aiv.outcome != 'skipped'
        uses: github/codeql-action/upload-sarif@5895b2c9be147bc7f1c75ce72c5cb577febb1907
        with:
          sarif_file: aiv-results.sarif

      - name: Preserve the AIV result
        if: always()
        run: test "${{ steps.aiv.outcome }}" = success
```

## 3. Open a pull request

AIV reads the complete base-to-head diff, freezes the `.aiv` policy from the protected base ref, and emits:

- a required-check result for blocking findings;
- JSON evidence;
- SARIF 2.1.0 for code-scanning upload;
- optional GitHub Check annotations;
- optional advisory, deterministic-findings, or review-tier labels.

Start with blocking syntax and invariant rules only. Keep design, change policy, external tools, JEV, and review routing advisory or disabled until you have repository-specific calibration evidence.

## Optional labels and comments

Add `pull-requests: write` only if needed. Then add these Action inputs:

```yaml
          label-pr-on-advisory: "true"
          label-deterministic-findings: "true"
          deterministic-findings-label: "aiv:findings"
          comment-deterministic-findings: "true"
          label-review-tier: "true"
```

The legacy `label-ai-slop`, `ai-slop-label`, and `comment-ai-slop` inputs remain supported for v1 compatibility. New policies should use deterministic-findings names and labels.

## Enable JEV separately

JEV is optional, advisory only, and requires a protected base policy plus a trusted AWS OIDC workflow. Follow [JEV on Amazon Bedrock](../integrations/jev-bedrock-adapter/README.md). Do not put AWS credentials in the ordinary pull-request AIV workflow.

## Troubleshooting

- **Capability handshake failed**: use the version published with `trusted-policy` support.
- **Base policy unavailable**: use `fetch-depth: 0`, pass the correct base ref, and commit `.aiv/` before enabling strict mode.
- **SARIF upload failed**: grant `security-events: write` and ensure the AIV step produced the file.
- **Label/comment publication failed on a fork**: fork pull requests often receive a read-only GitHub token. The AIV verdict remains valid.

For the full configuration surface, see [DEVELOPER-CONFIGURATION.md](DEVELOPER-CONFIGURATION.md) and [DEPLOYMENT.md](DEPLOYMENT.md).
