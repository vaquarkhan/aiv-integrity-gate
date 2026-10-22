# AIV quick-start tutorial

This page gets the current source build running on one repository. For a guided lab that exercises **all 16 gates one by one**, continue to [AIV Hello World](HELLO-WORLD.md).

![AIV deployment path](images/aiv-deployment-map.png)

**Figure 1: Start small and move the same verdict into CI.** The developer path gives fast local feedback. CI adds trusted-policy enforcement and machine reports. Agents and AI-DLC call the same core through `aiv verify`.

## 1. Build the current source version

Release 2.0 includes the policy snapshot, schema-v2 verify, strict JSON, the AI-DLC plugin, and the hardened Action. Build tag `v2.0`, or download `aiv-cli-2.0.jar` when that coordinate is on Maven Central.

```bash
git clone https://github.com/vaquarkhan/aiv-integrity-gate.git
cd aiv-integrity-gate
mvn clean verify
export AIV_JAR="$(pwd)/aiv-cli/target/aiv-cli-2.0.jar"
java -jar "$AIV_JAR" verify --capabilities
```

Expected capability output includes `strict-options`, `trusted-policy`, `policy-tree-digest`, `external-input-disclosure`, and `audit-chain`.

## 2. Choose a starting profile

```bash
# smallest: syntax + invariant only
java -jar "$AIV_JAR" init --preset minimal --workspace /path/to/project

# balanced Java repository
java -jar "$AIV_JAR" init --preset java-ci --workspace /path/to/project

# broad generated-change checks
java -jar "$AIV_JAR" init --preset agent-paste-strict --workspace /path/to/project
```

Every shipped preset explicitly names all 16 gates. Read [MODULE-ADOPTION-GUIDE.md](MODULE-ADOPTION-GUIDE.md) before enabling optional network/model/tool-backed gates.

## 3. Commit policy to the primary branch

```bash
cd /path/to/project
git add .aiv
git commit -m "Add AIV integrity policy"
```

Policy-bearing files belong under `.aiv/`:

```text
.aiv/
  config.yaml
  design-rules.yaml
  doc-rules.yaml
  baseline.txt
  rules/team.yaml
```

AIV pins the base ref to one commit, snapshots these files, and makes policy-bearing gates consume the frozen copy.

## 4. Run locally

```bash
java -jar "$AIV_JAR" --workspace . --diff origin/main --head HEAD
```

Exit codes:

- `0`: blocking gates passed.
- `1`: at least one blocking gate failed.
- `2`: invalid option, input, configuration, or output/integration problem.
- `3`: Git diff/provider failure.

## 5. Write machine reports

```bash
java -jar "$AIV_JAR" --workspace . --diff origin/main --head HEAD \
  --output-json aiv-report.json \
  --output-sarif aiv-results.sarif
```

JSON carries every gate, finding, severity, and notice. SARIF maps blockers to `error`, advisory findings to `warning`, and visible unverified evidence to `note`.

## 6. Enable strict CI policy

```bash
java -jar "$AIV_JAR" --workspace . --diff origin/main --head HEAD \
  --require-trusted-config \
  --output-json aiv-report.json \
  --output-sarif aiv-results.sarif
```

Strict mode refuses fallback, ignored/untracked `.aiv` extras, symlinks, path escapes, and any policy-tree mismatch. A pull request cannot replace its own yardstick.

![Immutable policy and evidence](images/aiv-trust-evidence.png)

**Figure 2: Strict policy comes from a pinned commit, not the proposed change.** The same snapshot is used for design, generic rules, documentation rules, baselines, evidence digests, and audit metadata.

## 7. Call AIV from an agent or tool

```bash
java -jar "$AIV_JAR" verify \
  --request change.json \
  --workspace . \
  --policy-ref origin/main \
  --require-trusted-policy \
  --audit-log .aiv/audit.log
```

Stdout is one schema-v2 JSON object. Diagnostics stay on stderr. Full request/response contract: [MCP.md](MCP.md).

## 8. Verify audit evidence

```bash
java -jar "$AIV_JAR" audit verify .aiv/audit.log
java -jar "$AIV_JAR" report --audit-log .aiv/audit.log --out aiv-evidence.html
```

Store the returned head outside the log when independent tamper evidence is required.

## 9. Add AI-DLC

```bash
python "$AIV_SOURCE/integrations/aidlc/aiv_aidlc_sensor.py" \
  --aiv-jar "$AIV_JAR" \
  --request change.json \
  --workspace . \
  --policy-ref origin/main \
  --blocking
```

The complete plugin provides advisory and fail-closed manifests. See [AIDLC.md](AIDLC.md).

## 10. Learn every gate

The full lab includes runnable examples for syntax, invariant, dependency, package existence, test adequacy, security, design, generic rules, density, cohesion, duplication, external SARIF, documentation integrity, PR metadata, license headers, and Jev:

**[Continue to AIV Hello World](HELLO-WORLD.md)**

## Reference paths

- [MODULE-ADOPTION-GUIDE.md](MODULE-ADOPTION-GUIDE.md): which modules/gates to use.
- [ARCHITECTURE.md](ARCHITECTURE.md): full architecture with eight explained PNG figures.
- [DEVELOPER-CONFIGURATION.md](DEVELOPER-CONFIGURATION.md): every key/default.
- [DEVELOPER-GUIDE.md](DEVELOPER-GUIDE.md): build and extend AIV.

**Author:** Viquar Khan
