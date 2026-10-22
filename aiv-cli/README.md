# Module: `aiv-cli`

`aiv-cli` is the runnable product distribution. It assembles every built-in gate and adapter into one shaded jar, establishes the trusted policy boundary, exposes human and machine interfaces, and wires audit, reports, proposals, and GitHub publication.

## Build

```bash
mvn -pl aiv-cli -am clean package
java -jar aiv-cli/target/aiv-cli-2.0.jar --version
```

Release 2.0 is `io.github.vaquarkhan:aiv-cli:2.0`. Tag `v2.0` builds the same jar.

## Commands

| Command | Purpose |
|---------|---------|
| default gate run | evaluate a Git diff and publish a human report |
| `doctor` | report/tune without CI-blocking exit |
| `verify` | strict JSON request in, one JSON verdict out |
| `init` | write starter config/preset |
| `explain <gate>` | offline gate help |
| `audit verify` | verify hash-chain integrity and optional external head |
| `report` | generate HTML evidence report and JSONL exports |
| `proposal check|scan` | evaluate template-driven improvement proposals |

## Normal gate run

```bash
java -jar aiv-cli.jar \
  --workspace . \
  --diff origin/main \
  --head HEAD \
  --require-trusted-config \
  --output-json aiv-report.json \
  --output-sarif aiv-results.sarif
```

### Important options

| Option | Behavior |
|--------|----------|
| `--workspace PATH` | source repository root |
| `--diff REF` / `--head REF` | diff endpoints |
| `--diff-json FILE` | strict in-memory diff, no Git required |
| `--config-from-workspace` | explicit opt-out from base-ref config |
| `--require-trusted-config` | fail closed on missing base config or any `.aiv` drift |
| `--audit-log FILE` | append a tamper-evident gate-run record |
| `--baseline FILE` | operator-selected baseline, copied into the frozen snapshot |
| `--include-doc-checks` | force doc-integrity on |
| `--fix` | remove only known merge/elision artifacts, then re-evaluate |
| `--quiet` | suppress human report |
| `--output-json` / `--output-sarif` | machine reports |
| `--publish-github-checks` | publish Check annotations |
| `--publish-pr-comments` | publish inline review comments |
| `--label-pr-on-advisory` | manage advisory label |
| `--label-ai-slop` / `--comment-ai-slop` | opt-in deterministic findings publication |
| `--warnings-exit-code N` | map passing run with notices to N |

Unknown options and missing values return exit 2. This is security-relevant because a misspelled trust flag cannot be ignored.

## Immutable policy flow

Every run resolves configuration once. If base-ref policy is available, the CLI materializes regular `.aiv` blobs directly from that commit into a private temporary snapshot. Workspace/fallback runs copy `.aiv` once. Symlinks, escapes, and configured policy references outside `.aiv/` are rejected. Design rules, generic rules, and baselines all consume this snapshot. It is deleted after gate evaluation.

`--require-trusted-config` additionally requires the complete live `.aiv` tree, including ignored files, to match the trusted commit and enables blocking `policy-integrity.changed` evidence.

## `aiv verify`

`verify` is the agent/MCP boundary. Stdout contains one JSON object; logs go to stderr.

```bash
printf '%s' '{"artifact":{"files":[{"path":"A.java","changeType":"MODIFIED","content":"class A {}"}]}}' |
  java -jar aiv-cli.jar verify --workspace .
```

Schema v2 includes:

- `verdict`, `blocking`, `passed`, `advisory`, gates/findings/notices,
- `evidence_digest` binding request bytes + canonical config + frozen sidecars + policy commit,
- `policy` source/ref/commit/fallback/digest,
- `reproducible_local` and `external_inputs`,
- optional `audit.record_hash`.

Use `--policy-ref origin/main --require-trusted-policy` for fail-closed agent calls. `verify --capabilities` is the positive handshake used by the composite Action.

## Reports and exit codes

| Exit | Meaning |
|------|---------|
| 0 | blocking gates passed; doctor also returns 0 |
| 1 | a blocking gate failed |
| 2 | invalid argument/config/input/report/integration error |
| 3 | Git provider failure |

JSON report schema v2 preserves hard/advisory flags. SARIF maps blockers to `error`, advisory failures to `warning`, and pass-with-unverified evidence to `note`.

## GitHub publication

Publication configuration is taken from the same resolved trusted config as the verdict. The PR cannot change which rules are labeled/commented. Environment inputs include `GITHUB_TOKEN`, `GITHUB_REPOSITORY`, `GITHUB_SHA`, PR number, title, and body.

## Adding a command or gate

For a command, keep parsing strict, machine stdout clean, and errors mapped to documented exits. Add command tests through `Main.run`. For a gate, create a plugin module, register ServiceLoader, add it to the shaded dependencies, default-off set if needed, explain resource, config docs, and tests.

## Tests

- `MainTest`: all command/flag/composition/publication/trust paths.
- `VerifyCommandTest`: strict request contract, policy digest/snapshot, capabilities, audit.
- `JsonReportWriterTest`, `SarifReportWriterTest`.
- `EvidenceCommandsTest`, `InitCommandTest`, `ExplainCommandTest`, config-provider tests.

```bash
mvn -pl aiv-cli -am verify
```

The module requires complete line coverage. Build the shaded jar with `clean package` after resource changes to avoid stale embedded presets/help.

## Module interactions

Depends on `aiv-core`, both adapters, every gate plugin, `aiv-audit`, `aiv-report`, and `aiv-proposal`. It is the composition root; plugin modules do not depend back on it.
