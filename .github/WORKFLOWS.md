# GitHub metadata and workflows

This directory configures **Dependabot**, **CI**, **release**, and **publishing** for the AIV Integrity Gate repository.

## Workflows

| File | Trigger | Purpose |
|------|---------|---------|
| **`workflows/aiv.yml`** | `push` to default branches, `pull_request` | Builds the reactor with `mvn clean verify -pl aiv-cli -am`, runs the shaded CLI on the diff, writes job summary, optionally posts a PR comment. |
| **`workflows/release-github.yml`** | Version tags `v*` | Builds and attaches the **`aiv-cli`** JAR to a GitHub Release. |
| **`workflows/publish-maven-central.yml`** | `workflow_dispatch` | Publishes signed artifacts to Maven Central (requires secrets). |
| **`workflows/assignment-gate.yml`** | (as configured) | Optional automation related to issue/PR assignment; see file for details. |

## Dependabot

**`dependabot.yml`** bumps GitHub Actions and other ecosystems on a schedule. Pin major versions in workflows when security policy requires SHA-pinned actions (this repo often pins full commit SHAs).

## Composite action

The repository root `action.yml` defines the hardened composite action. It resolves a caller-supplied compatible jar (path, URL, or compatible release version), requires a positive capability handshake, runs strict trusted policy, and emits JSON/SARIF with optional GitHub publication.

**Author:** Viquar Khan
