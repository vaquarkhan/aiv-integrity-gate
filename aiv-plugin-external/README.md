# `aiv-plugin-external`

This module contributes the `external` quality gate. It adapts trusted command
line analyzers that emit SARIF 2.1.0 and folds their findings into the AIV
verdict. It does not reimplement language, security, or policy analyzers.

## Architecture role

`ExternalToolsGate` owns command selection, process lifecycle, changed-file
scoping, and result aggregation. Production execution uses `ProcessBuilder`
behind a `Runner` seam. `SarifReader`, built on the API module's `JsonLite`,
extracts a stable subset of SARIF into AIV findings.

No analyzer is bundled or downloaded. Tool installation, executable trust, and
version pinning belong to the environment that runs AIV.

## Execution flow

For each configured tool:

1. Build the changed-path set and filter it with tool-specific extensions or
   languages.
2. Replace a standalone `{files}` command argument with individual changed
   paths. If that placeholder is present and no path matches, do not run the
   tool.
3. Start the command directly in the source workspace without shell parsing.
4. Drain stdout on a separate thread so pipe backpressure cannot defeat the
   timeout. Stderr is discarded.
5. Wait up to `timeout_seconds`. On timeout, forcibly terminate descendants and
   then the parent process.
6. Read SARIF from stdout or the configured workspace-relative or absolute file.
7. Parse `runs[].results[]`, filter by `min_level`, and optionally retain only
   changed-file locations.
8. Convert retained results and aggregate blocking, advisory, and lifecycle
   outcomes.

A nonzero exit code does not decide the verdict. Valid SARIF is processed
regardless of exit code.

## Rule IDs

| Rule ID | Meaning |
|---|---|
| `external.<tool-id>.<sarif-rule-id>` | Analyzer result. Missing SARIF rule IDs become `unknown`. |
| `external.<tool-id>.missing` | Command could not be started and `on_missing` is `fail`. |
| `external.<tool-id>.timeout` | Command timed out and `on_missing` is `fail`. |
| `external.<tool-id>.no-output` | A completed command produced no readable SARIF. |
| `external.<tool-id>.bad-output` | Output could not be parsed as supported SARIF. |

Tool IDs are trimmed and normalized to lowercase.

## Configuration and defaults

The gate is off when `tools` is absent or empty. Each tool entry requires `id`
and a nonempty command list.

| Tool key | Default | Meaning |
|---|---|---|
| `command` | Required | Argument list passed directly to `ProcessBuilder`. |
| `sarif` | `stdout` | SARIF source, either stdout or a file path. |
| `severity` | Blocking | Only `warn` makes analyzer findings advisory. |
| `min_level` | `warning` | Minimum SARIF level: `note`, `warning`, or `error`. |
| `changed_files_only` | `true` | Drop located results outside changed paths. |
| `file_extensions` | Unrestricted | Optional eligible suffixes for `{files}`. |
| `languages` | Unrestricted | Optional language aliases for `{files}`. |
| `timeout_seconds` | `120` | Process timeout. |
| `on_missing` | `skip` | Use `fail` to block on missing commands and timeouts. |

## Example

```yaml
- id: external
  enabled: true
  severity: block
  config:
    tools:
      - id: ruff
        command: [ruff, check, --output-format, sarif, "{files}"]
        sarif: stdout
        severity: warn
        min_level: warning
        changed_files_only: true
        languages: [python]
        timeout_seconds: 60
        on_missing: fail
```

`{files}` expands only when it is an entire argument. A string such as
`--files={files}` is passed unchanged.

## Hard and advisory behavior

Analyzer findings are advisory only when that tool has `severity: warn`.
Anything else is blocking. Missing commands and timeouts are `SKIPPED` notes by
default, or blocking `.missing` and `.timeout` findings when `on_missing: fail`.
Interruption restores the thread interrupt flag and returns a `SKIPPED` note.

Once a command has run, absent SARIF and invalid SARIF always block through
`.no-output` or `.bad-output`, regardless of tool severity or `on_missing`.
This distinguishes an unavailable optional tool from a tool that ran without
producing trustworthy evidence.

## SARIF lifecycle

The reader consumes each result's `ruleId`, `level`, message text, and first
physical location. Defaults are `unknown`, `warning`, an empty message, and line
1. It normalizes slashes and common `file://` and leading `./` forms. Located
results outside the changed set are dropped when changed-only filtering is
enabled. Locationless findings remain attached to `(diff)`.

Only SARIF-compatible JSON is accepted. The adapter does not infer findings
from process exit status or stderr.

## Inputs and outputs

Inputs are trusted tool configuration, changed paths, source workspace, and
subprocess SARIF. Output is a pass, advisory, or fail `GateResult` containing
normalized findings and execution notes. The plugin runs commands in the source
workspace, not the policy workspace.

The host is responsible for sourcing the tool list from protected policy when a
change must not disable its own analyzers. This plugin trusts the configuration
in `AIVContext`; it does not resolve or validate a base Git revision itself.

## Interactions and extension points

Use this adapter for tools such as linters, static analyzers, and secret
scanners that can produce SARIF. The `Runner` seam supports controlled process
tests or alternative launchers, and SARIF is the integration contract for new
tools. Pair external findings with deterministic `design` and `rules` checks
when local policy needs no external executable.

## Honest boundaries

Stderr is discarded. Only stdout is captured. Only the first SARIF location is
retained. Path comparison is normalized but case-sensitive. Unknown
`min_level` values rank below known levels. A configured SARIF file is not
removed before execution, so tool wrappers should overwrite it atomically to
avoid stale output. Timeout termination requests descendant and parent
termination, but the implementation does not wait until every descendant is
confirmed dead. Commands are not sandboxed, downloaded, version-pinned, or
restricted from network access by this module. Configure only tools already
trusted by the execution environment.

## Tests and build

`ExternalToolsGateTest` covers stdout and file SARIF, file expansion, level and
changed-path filtering, Windows paths, missing and timeout policies,
interruption, invalid and absent output, real process timeout, missing binaries,
and parser defaults.

From this module:

```shell
mvn test
```

From the parent reactor:

```shell
mvn -pl aiv-plugin-external test
```

See also [developer configuration](../docs/DEVELOPER-CONFIGURATION.md) and
`aiv explain external`.

**Author:** Viquar Khan
