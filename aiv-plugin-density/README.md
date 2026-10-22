# `aiv-plugin-density`

This module contributes the `density` quality gate. It provides inexpensive,
generic signals for unusually low-information changed files: Shannon character
entropy for configured file types and a Java logic density ratio (LDR).

## Architecture role

`DensityGate` runs after the host has assembled an `AIVContext`. It is a
heuristic reviewer aid, not an authorship detector. JavaParser supplies the Java
17 syntax tree used for LDR. Shared `FileExtensions` logic determines which
changed files are eligible.

## Execution flow

1. If the commit author email is listed in `trusted_authors` and the head commit
   is signed, pass without scanning files.
2. Read thresholds and file filters.
3. Skip empty or ineligible changed files.
4. Apply the per-file net line-count refactor exemption.
5. For files at least `entropy_min_bytes` long, compute Shannon character
   entropy and compare it with the effective threshold.
6. For Java files, parse the source and compute LDR.
7. Return findings anchored at the first nonblank source line.

LDR weights `if`, `for`, `while`, and `switch` nodes as five logic points,
method calls and binary expressions as two logic points, and classes and
methods as one structure point. The ratio is logic divided by logic plus
structure. A file containing only top-level interfaces is normalized to 1.0 so
port-style interfaces are not treated as low-density code.

The effective entropy threshold starts at `entropy_threshold`, subtracts 0.2
for non-Java files, subtracts 0.4 for files below 800 bytes, and never drops
below 2.5.

## Rule IDs

| Rule ID | Meaning |
|---|---|
| `density.entropy` | Character entropy is below the effective threshold. |
| `density.ldr` | Java logic density is below `ldr_threshold`. |

## Configuration and defaults

| Key | Default | Meaning |
|---|---:|---|
| `ldr_threshold` | `0.25` | Minimum Java LDR. A value at or below zero disables this finding. |
| `entropy_threshold` | `4.0` | Base minimum character entropy. |
| `entropy_min_bytes` | `300` | Minimum UTF-8 byte length for entropy evaluation. |
| `refactor_net_loc_threshold` | `-50` | Exempt a file whose net line count is at or below this value. |
| `trusted_authors` | `[]` | Author emails eligible for the signed-commit bypass. |
| `file_extensions` | Shared AIV defaults | Eligible suffixes. |
| `languages` | Shared AIV defaults | Language aliases resolved by `FileExtensions`. |

Threshold values must be numeric YAML values. Values of another type fall back
to the default.

## Example

```yaml
- id: density
  enabled: true
  severity: warn
  config:
    ldr_threshold: 0.25
    entropy_threshold: 4.0
    entropy_min_bytes: 300
    refactor_net_loc_threshold: -50
    languages: [java, python]
    trusted_authors: []
```

## Hard and advisory behavior

The plugin returns `GateResult.fail` when either heuristic fires. It does not
create advisory results directly. Configure the surrounding gate with warning
severity when density should be a reviewer signal. This is the recommended
starting posture because repository benchmarks did not establish that density
reliably separates generated code from legitimate code. Blocking use requires
calibration against the target repository's accepted changes.

The trusted-author bypass requires both a case-insensitive email match and a
signed head commit. Email matching is still not a complete identity or trust
system, and the plugin does not validate the signer against the listed email.

## Inputs and outputs

Inputs are changed-file paths and contents, per-file net line counts, author
email, signed-head metadata, and gate configuration. Outputs are a pass or fail
`GateResult` and 1-based findings at the first nonblank line. The gate does not
read or write repository files.

## Interactions and extension points

Density complements deterministic design and rules checks but should not
replace them. Configure `file_extensions` or `languages` to change entropy
coverage, tune thresholds from repository history, and use the refactor
threshold to avoid flagging deletion-heavy cleanup. Java LDR can be extended by
adding explicit syntax-node weights and corresponding tests.

## Honest boundaries

Entropy is a coarse character statistic. Repetitive but valid data and source
can score low, while polished low-value code can score high. LDR is Java-only,
and Java parse failure produces an LDR of zero. The refactor exemption uses
per-file net lines, not semantic refactoring evidence. Trusted authors can skip
all checks only under the signed-head condition, but the email list itself is
configuration, not cryptographic identity. The gate does not infer whether a
human or model wrote a change.

## Tests and build

`DensityGateTest` covers service loading, both rule IDs, Java node weighting,
interface normalization, entropy adjustments, refactor exemptions, disabled
thresholds, file filtering, and the signed trusted-author condition.

From this module:

```shell
mvn test
```

From the parent reactor:

```shell
mvn -pl aiv-plugin-density test
```

See also [developer configuration](../docs/DEVELOPER-CONFIGURATION.md).

**Author:** Viquar Khan
