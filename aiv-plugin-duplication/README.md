# `aiv-plugin-duplication`

This module contributes the `duplication` quality gate. It looks for newly
added logical blocks that already exist in unchanged workspace files and
reports them as reviewer prompts.

## Architecture role

`DuplicationGate` builds a small set of probes from changed content, then
streams the workspace once. `LineNormalizer` removes low-signal lines and
normalizes whitespace before fixed-size windows are compared. The design avoids
a repository-wide in-memory index and favors precise exact matches over broad
similarity detection.

## Execution flow

1. Read limits, test-file policy, and file filters.
2. Extract normalized added-line text from the unified diff.
3. For a newly added file, consider its whole normalized content. For a
   modified file, retain normalized lines represented in the diff index.
4. Build a key for each `min_lines` window of significant lines.
5. Walk the source workspace once, skipping changed files, excluded directories,
   ineligible extensions, oversized files, and tests unless enabled.
6. Compare normalized windows with the probe keys.
7. Emit one finding for each changed-file and existing-file pair, including the
   first changed and existing line numbers.

Normalization collapses whitespace and ignores short or structural-only lines,
blank lines, common comments, annotations, imports, package declarations,
braces, and simple control markers such as bare `return`, `break`, `continue`,
`else`, `try`, and `finally`.

## Rule ID

`duplication.clone` reports an added block of at least `min_lines` significant
lines that exactly matches normalized existing code.

## Configuration and defaults

| Key | Default | Meaning |
|---|---:|---|
| `min_lines` | `6` | Significant lines in a comparison window. |
| `max_files` | `5000` | Maximum workspace files visited before scanning stops. |
| `max_file_bytes` | `524288` | Maximum size of an existing candidate file. |
| `include_tests` | `false` | Include changed and existing test files. |
| `file_extensions` | Shared AIV defaults | Eligible suffixes. |
| `languages` | Shared AIV defaults | Language aliases resolved by `FileExtensions`. |

Only numeric values are accepted for numeric keys. Values of another type use
the default.

## Example

```yaml
- id: duplication
  enabled: true
  severity: warn
  config:
    min_lines: 8
    max_files: 5000
    max_file_bytes: 524288
    include_tests: false
    languages: [java, python, typescript]
```

## Hard and advisory behavior

Clone findings always use `GateResult.advisory`. The implementation never
returns a blocking result for a match. Missing or unreadable workspaces,
workspace scan errors, binary or unreadable files, and absent probes produce no
finding. This precision-first behavior keeps an optional reviewer signal from
breaking a build because repository scanning was unavailable.

## Inputs and outputs

Inputs are the raw unified diff, changed-file paths, contents and change types,
the current source workspace, and gate configuration. Output is a pass or
advisory `GateResult`. Findings use the changed file and its first matching
line; the message also names the existing file and line.

## Interactions and extension points

Use this gate beside `design`, `rules`, and language analyzers. It detects reuse
opportunities that local pattern rules do not express, while external clone
analyzers can provide broader similarity matching. Extend language coverage
through `file_extensions` or `languages`. Package-private workspace walker and
reader seams support deterministic I/O tests. New normalization rules should be
paired with cases proving they do not collapse materially different code.

The scanner skips `.git`, `.aiv`, dependency directories, generated output,
build output, virtual environments, IDE metadata, and common vendor trees.

## Honest boundaries

Matching is exact after normalization. Renamed identifiers, reordered
statements, extracted helpers, and near-duplicates are not found. Changed files
are excluded as match sources, so duplication introduced between two files in
the same change is not detected. A modified file without a usable added-line
index is not probed. Identical probe keys retain only one originating window.
`max_files` counts visited files before extension filtering, so a large mixed
workspace can reach the cap early. Scan failures intentionally do not block or
create a separate warning.

## Tests and build

`DuplicationGateTest` covers service loading, advisory clone findings,
added-line scoping, test inclusion, resource limits, I/O failure behavior,
normalization, and the walker and reader seams.

From this module:

```shell
mvn test
```

From the parent reactor:

```shell
mvn -pl aiv-plugin-duplication test
```

See also [developer configuration](../docs/DEVELOPER-CONFIGURATION.md).

**Author:** Viquar Khan
