# `aiv-plugin-design`

This module contributes two `QualityGate` implementations through Java
`ServiceLoader`: `design` and `cohesion`. The design gate enforces small,
project-defined dependency and API constraints. The cohesion gate measures
whether a change spans too many path areas or files. Keeping both gates here
makes cohesion part of change design review rather than a source-code density
or duplication heuristic.

## Architecture role

`DesignComplianceGate` evaluates changed file contents against declarative YAML
constraints. `DesignRulesLoader` parses the YAML with SnakeYAML's safe
constructor, `JavaDesignSurface` removes Java comments and string literals for
normal matching, and shared `FileExtensions` logic limits eligible files.

`CohesionGate` is a separate gate in the same plugin. It maps each changed path
to an area made from directory segments, then reports broad changes that may be
easier to review as separate pull requests. It does not inspect dependencies,
semantics, or ownership. This location is intentional: cohesion describes the
shape and reviewability of a proposed design change, while density describes
content heuristics and duplication compares code blocks.

## Execution flow

### `design`

1. Read the `design` gate configuration.
2. Resolve `rules_path` against `AIVContext.getPolicyWorkspace()`.
3. Load and validate constraints.
4. Filter changed files by configured extensions or languages.
5. Build a matching surface. Valid Java uses a surface without comments,
   strings, or text blocks. Other files and Java that cannot be parsed use raw
   text. Constraints with an ID containing `ai-generated` intentionally use raw
   text so attribution markers remain visible.
6. Apply optional keywords to the content or path.
7. Check required and forbidden literal patterns and return findings.

Matching is case-insensitive and boundary-aware, not regular expression
matching. For example, `System.exit` matches `System . exit`, but does not match
`MySystem.exit`.

### `cohesion`

1. Read the `cohesion` gate configuration.
2. Count all changed files.
3. Convert each path to up to `area_depth` directory segments, excluding the
   filename. A root-level file belongs to `(root)`.
4. Compare file and distinct-area counts with enabled limits.
5. Return findings at the diff level when a limit is exceeded.

## Rule IDs

| Gate | Rule ID | Meaning |
|---|---|---|
| `design` | `design.forbidden.<constraint-id>` | A forbidden literal is present. |
| `design` | `design.required.<constraint-id>` | A required literal is absent. |
| `cohesion` | `cohesion.areas` | The change exceeds `max_areas`. |
| `cohesion` | `cohesion.files` | The change exceeds `max_files`. |

Required-call findings use line 1. Forbidden-call findings use the first
case-insensitive occurrence in the original file and can also fall back to line
1.

## Configuration and defaults

### `design`

| Key | Default | Meaning |
|---|---|---|
| `rules_path` | `.aiv/design-rules.yaml` | Constraint file in the policy workspace. |
| `file_extensions` | Shared AIV defaults | Eligible suffixes. |
| `languages` | Shared AIV defaults | Language aliases resolved by `FileExtensions`. |

A constraint supports `id`, `keywords`, `forbidden_calls`, and
`required_calls`. The three lists default to empty.

The default rules file is optional. If it is absent, the gate passes with no
constraints. A configured non-default path that is absent fails closed with a
configuration error. Invalid or unreadable YAML also fails closed.

### `cohesion`

| Key | Default | Meaning |
|---|---:|---|
| `max_areas` | `3` | Maximum distinct path areas. A value at or below zero disables this check. |
| `area_depth` | `2` | Directory segments used for an area. Values are clamped to at least 1. |
| `max_files` | `0` | Maximum changed files. Zero disables this check. |

Numeric strings are accepted. Invalid values fall back to the defaults.

## Example

```yaml
- id: design
  enabled: true
  severity: block
  config:
    rules_path: .aiv/design-rules.yaml
    languages: [java]

- id: cohesion
  enabled: true
  severity: warn
  config:
    max_areas: 3
    area_depth: 2
    max_files: 40
```

```yaml
# .aiv/design-rules.yaml
constraints:
  - id: no-process-termination
    keywords: [service]
    forbidden_calls: [System.exit]
    required_calls: []
```

## Hard and advisory behavior

Both implementations return `GateResult.fail` when they find violations. They
do not return advisory results themselves. A host configuration can classify a
gate as warning severity, as in the cohesion example, but that conversion is a
host responsibility. Invalid explicitly configured design policy is an error,
not an advisory finding.

## Immutable policy workspace

The design rules are read from the policy workspace, not from the source
workspace under review. In a trusted run, the host can provide an immutable
snapshot of `.aiv` from the selected policy revision. This prevents a proposed
change from weakening the rules used to evaluate itself and ensures one frozen
set of policy bytes is used for the evaluation. The plugin consumes the policy
workspace supplied in `AIVContext`; it does not resolve a Git reference or prove
immutability by itself.

## Inputs and outputs

Inputs are the changed-file paths and contents, gate configuration, and the
policy-side rules file for `design`. Cohesion consumes changed paths only.
Outputs are `GateResult` values with pass or fail state, a summary, and
1-based `Finding` locations. Cohesion findings use `(diff)` and line 1.

## Interactions and extension points

Use `aiv-plugin-rules` for regular expressions, call-argument checks,
allowlists, or hollow-function detection. Add design constraints in YAML for
simple project-specific required or forbidden literals. Extend eligible file
coverage through `file_extensions` or `languages`. New matching semantics
should be implemented explicitly rather than hidden inside constraint text.

## Honest boundaries

The design gate does not perform symbol resolution, dependency graph analysis,
or semantic call resolution. Non-Java files use raw text. Java parse failure
also falls back to raw text, which can match comments or strings. Reported lines
come from the unsanitized source and may point to an earlier textual occurrence.
A missing default rules file disables design checks without an error.

Cohesion is a path-count heuristic. Directory layout may not represent actual
architectural responsibility, and a broad but coherent migration can exceed
its thresholds. It samples at most eight sorted areas in its message.

## Tests and build

Tests cover policy-workspace precedence, missing and invalid rules, matching
boundaries, Java comment and string removal, file filtering, and cohesion area
and file limits.

From this module:

```shell
mvn test
```

From the parent reactor:

```shell
mvn -pl aiv-plugin-design test
```

See also [developer configuration](../docs/DEVELOPER-CONFIGURATION.md).

**Author:** Viquar Khan
