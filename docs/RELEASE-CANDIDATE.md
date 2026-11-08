# Hardened release candidate checklist

This is the local handoff for the first AIV release that supports the hardened composite Action. It does not publish artifacts, create tags, or update remote references.

## Release conditions

Before choosing a release version, confirm all of the following from the release commit:

- `mvn -B -ntp clean verify` passes.
- `java -jar aiv-cli/target/aiv-cli-<version>.jar verify --capabilities` returns `"status":"ok"` and includes `trusted-policy`.
- The same JAR contains `io/github/vaquarkhan/aiv/cli/Main.class` and bundled runtime dependencies.
- `action.yml` downloads the correct Maven Central coordinate:

  ```text
  io/github/vaquarkhan/aiv-cli/<version>/aiv-cli-<version>.jar
  ```

- The release notes identify the immutable Action commit SHA.
- The GitHub release tag and root `pom.xml` use the identical version.
- All source and packaged presets contain the same explicit 18-gate policy.
- The JEV adapter and Terraform validation suites pass.

## Required remote operations

These operations are intentionally manual and require explicit authorization:

1. Commit the reviewed release candidate.
2. Create and push immutable version tag `v<version>`.
3. Verify the GitHub release workflow builds the tag and attaches the shaded CLI JAR.
4. Run the Maven Central publish workflow against the same tag using approved publishing credentials.
5. Verify the Central JAR download, checksum, CLI version, and capability handshake.
6. Update the protected floating major Action reference only after the immutable release works.
7. Replace the `<RELEASED_VERSION>` and `<IMMUTABLE_ACTION_COMMIT_SHA>` placeholders in [GITHUB-QUICKSTART.md](GITHUB-QUICKSTART.md) with verified release values.

Do not change a published tag or overwrite a release asset. Publish a follow-up version for fixes.

## Post-release smoke test

In a clean sample repository, without Maven or copied AIV source:

1. Add the 18-gate starter `.aiv/config.yaml` from [GITHUB-QUICKSTART.md](GITHUB-QUICKSTART.md).
2. Add the workflow using the released JAR version and immutable Action SHA.
3. Open a pull request with an objective invariant violation.
4. Confirm the required check fails, JSON and SARIF exist, and SARIF appears in code scanning.
5. Fix the violation and confirm the check passes.
6. If labels are enabled, confirm `aiv:findings` is added and removed idempotently.

This is the success criterion for the first installation milestone: a new repository enables AIV without building Maven or copying AIV source.
