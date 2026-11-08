# GitHub Actions workflow definitions

YAML workflows in this folder are picked up automatically by GitHub.

| Workflow | Purpose |
|----------|---------|
| **`aiv.yml`** | Primary CI: build with Maven, run AIV on PR/push, job summary, optional PR comment. |
| **`release-github.yml`** | Tag-driven release asset upload. |
| **`publish-maven-central.yml`** | Manual publish to Maven Central. |
| **`jev-bedrock-review.yml`** | Opt-in trusted JEV Bedrock review: protected-base checkout, PR Git objects as data, OIDC, loopback adapter, strict JSON evidence receipt. |
| **`terraform-validate.yml`** | Terraform format, module tests, validation, and static credential/personal-data checks; no AWS credentials or apply. |
| **`assignment-gate.yml`** | Optional automation for assignment rules; see file comments. |

Pin action versions to full commit SHAs where security policy requires reproducible builds. **Dependabot** (see `../dependabot.yml`) proposes updates.

See also the project [README](../../README.md).

**Author:** Viquar Khan
