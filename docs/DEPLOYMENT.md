# Deploy AIV into a repository

AIV is a local/CI executable, not a long-running server. Deployment means placing a compatible CLI jar in the workflow, committing explicit `.aiv/` policy, and selecting the result surfaces required by the repository.

![AIV deployment topology](images/aiv-deployment-map.png)

**Figure 1: Three deployment surfaces share one engine.** Developers run a small profile before commit. CI runs a strict policy-bound profile and publishes machine evidence. Agents and lifecycle tools call the schema-v2 verify contract.

## 1. Choose a distribution channel

### Stable core release

Maven Central 1.0.4 and the `@v1` Action provide the stable core gate:

```bash
curl -fsSL -o aiv-cli.jar \
  https://repo1.maven.org/maven2/io/github/vaquarkhan/aiv/aiv-cli/1.0.4/aiv-cli-1.0.4.jar
```

### Current hardened source

The immutable policy tree, strict RFC JSON, schema-v2 verify, AI-DLC plugin, and hardened Action are next-release source capabilities:

```bash
git clone https://github.com/vaquarkhan/aiv-integrity-gate.git
cd aiv-integrity-gate
mvn clean verify
export AIV_JAR="$(pwd)/aiv-cli/target/aiv-cli-1.0.4.jar"
java -jar "$AIV_JAR" verify --capabilities
```

A compatible jar reports a successful capability handshake containing `trusted-policy` and `policy-tree-digest`.

For consumer CI before the release, publish the source-built jar as an internal immutable artifact and pass it through `cli-jar-path` or `cli-jar-url`. Do not clone a mutable default branch in every consumer build.

## 2. Choose an explicit profile

```bash
java -jar "$AIV_JAR" init --preset minimal --workspace /path/to/project
java -jar "$AIV_JAR" init --preset java-ci --workspace /path/to/project
java -jar "$AIV_JAR" init --preset agent-paste-strict --workspace /path/to/project
```

All presets explicitly name all 16 gates. See [MODULE-ADOPTION-GUIDE.md](MODULE-ADOPTION-GUIDE.md) for gate/module prerequisites and profile matrices.

## 3. Commit policy

```text
.aiv/
  config.yaml
  design-rules.yaml
  doc-rules.yaml
  baseline.txt
  rules/team.yaml
```

Keep policy inputs under `.aiv/`. Commit them to the primary branch and protect changes through review. AIV pins the base ref to one commit OID and materializes those Git blobs into an immutable snapshot for each run.

## 4. Local developer path

```bash
java -jar "$AIV_JAR" --workspace . --diff origin/main --head HEAD
```

For staged/pre-commit setup, see [PRE-COMMIT.md](PRE-COMMIT.md). Local profiles should stay small enough to give useful feedback without depending on services that are unavailable on developer machines.

## 5. Strict CI command

```bash
java -jar "$AIV_JAR" \
  --workspace . \
  --diff origin/main \
  --head HEAD \
  --require-trusted-config \
  --output-json aiv-report.json \
  --output-sarif aiv-results.sarif \
  --audit-log .aiv/audit.log
```

Strict mode fails when base policy is unavailable, the live `.aiv` tree differs, ignored extras exist, a symlink/path escape is present, or a blocking gate fails.

## 6. Hardened composite Action

The current source Action intentionally has no unsafe default release version. Supply a compatible jar:

```yaml
name: AIV
on: [pull_request]
permissions:
  contents: read
  checks: write
  pull-requests: write
jobs:
  aiv:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5
        with:
          fetch-depth: 0
      - uses: vaquarkhan/aiv-integrity-gate@main
        with:
          base-ref: origin/${{ github.base_ref }}
          cli-jar-url: https://artifacts.example/aiv/aiv-cli-compatible.jar
          publish-github-checks: "true"
          label-pr-on-advisory: "true"
```

Use an immutable/digest-protected internal artifact URL. After a hardened release, set `aiv-version` to that compatible release.

The Action:

1. sets up Java,
2. resolves the jar from path/URL/version,
3. requires a positive `verify --capabilities` handshake,
4. runs with `--require-trusted-config`,
5. writes JSON and SARIF,
6. optionally publishes Checks/labels/comments.

## 7. GitHub permissions

Grant only what is used:

```yaml
permissions:
  contents: read
  checks: write          # only for --publish-github-checks
  pull-requests: write   # only for labels/comments
```

Fork PR tokens may not have write permission. Explicit publication errors are reported; the gate result remains separate from publication.

## 8. Other CI systems

Any system that can run Java can execute AIV:

```bash
java -jar aiv-cli.jar --workspace . --diff "$BASE_REF" --head "$HEAD_REF" \
  --require-trusted-config --output-json aiv-report.json --output-sarif aiv-results.sarif
```

The requirements are:

- full Git history/base ref available,
- JDK 17+,
- compatible AIV jar,
- `.aiv/` policy on the base branch,
- optional external tool/registry/model prerequisites for enabled gates.

See [CI-SNIPPETS.md](CI-SNIPPETS.md).

## 9. Agent and MCP-style integration

```bash
java -jar aiv-cli.jar verify --request change.json --workspace . \
  --policy-ref origin/main --require-trusted-policy
```

Stdout is one JSON object; stderr contains diagnostics. See [MCP.md](MCP.md).

## 10. AI-DLC integration

Use the complete plugin under `integrations/aidlc/plugin/`. Advisory file mode gives fast feedback; blocking full-request mode requires trusted policy and rejects undeclared external inputs. See [AIDLC.md](AIDLC.md).

## 11. Evidence retention

`--audit-log` writes an OS-locked hash chain. Preserve the returned head outside the log for independent verification:

```bash
java -jar aiv-cli.jar audit verify .aiv/audit.log --expect-head <head>
java -jar aiv-cli.jar report --audit-log .aiv/audit.log --out aiv-evidence.html
```

Choose retention/access control based on the paths/messages/metadata your records contain.

## 12. Deployment checklist

- [ ] compatible jar passes `verify --capabilities`
- [ ] explicit profile lists all 16 gates
- [ ] policy sidecars live under `.aiv/`
- [ ] `.aiv/` is committed to the primary branch
- [ ] CI fetches complete base history/ref
- [ ] strict CI uses `--require-trusted-config`
- [ ] external gate tools are pinned/installed
- [ ] registry/model dependencies are intentional
- [ ] JSON/SARIF artifacts are retained as needed
- [ ] GitHub permissions are least privilege
- [ ] audit head is externally anchored if tamper evidence is required
- [ ] project has documented suppression/override review

## 13. Verification

Run a clean change and one intentional failure. Confirm:

- expected exit code,
- finding rule/path/line,
- SARIF severity,
- policy commit/source in verify output,
- Action capability handshake,
- audit chain verification.

Full lab: [HELLO-WORLD.md](HELLO-WORLD.md).

**Author:** Viquar Khan
