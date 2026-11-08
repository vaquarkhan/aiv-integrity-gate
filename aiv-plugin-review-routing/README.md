# Module: `aiv-plugin-review-routing` (gate `review-routing`)

The review routing gate computes a deterministic review tier for a change (for example `none`, `lite`,
`balanced`) from the paths it touches and its size, so AI review automation can decide whether to ask a
model at all and at what effort. It never fails and never blocks.

## Architecture role

Default-off gate (listed in `DEFAULT_OFF_GATES`). It reads only the diff in `AIVContext`, plus the files
that `exclude_paths` removed (`AIVContext.EXCLUDED_CHANGED_FILES`), and returns one finding. It never
calls GitHub; `aiv-cli --label-review-tier` publishes the tier through `aiv-adapter-github`.

## Runtime flow

1. Read and validate the gate config. Invalid config is an error (exit 2), never a silent default.
2. Collect every changed path, including excluded files and the old path of a rename.
3. Count lines changed (added plus deleted) and files changed.
4. Evaluate rules in order. A rule matches when every condition it lists holds. First match wins.
5. Return a passed result with one finding `review-routing.tier`, file `(diff)`, message
   `tier=<name>; <reason>; lines=<n>; files=<n>`.

## Configuration

```yaml
gates:
  - id: review-routing
    enabled: true
    config:
      default_tier: lite
      tiers:
        - tier: none               # no AI review: CI definitions, security policy
          paths: [".github/workflows/**", "SECURITY.md", "**/security/**"]
        - tier: balanced
          min_lines_changed: 400
        - tier: balanced
          min_files_changed: 20
        - tier: balanced
          paths: ["core/**"]
          min_lines_changed: 50
```

| Key | Meaning |
|---|---|
| `default_tier` | tier when no rule matches (default `lite`) |
| `tiers[].tier` | lower-case letters, digits, hyphens; at most 30 characters |
| `tiers[].paths` | globs with `exclude_paths` semantics; any changed path matching counts |
| `tiers[].min_lines_changed` | non-negative integer |
| `tiers[].min_files_changed` | non-negative integer |

Each rule needs at least one condition.

## Findings

Exactly one finding, `review-routing.tier`, on every run. It lands in the JSON report under a `pass`
partition and in SARIF as a `note`, so it never changes the verdict or the exit code.

## Interactions

- `aiv-cli --label-review-tier [prefix]` (or `AIV_LABEL_REVIEW_TIER=true`) sets exactly one PR label
  `aiv:review-<tier>` and removes other labels with the same prefix. It exits 2 if this gate is off or
  the GitHub environment is missing, so a workflow that asked for routing never runs without it.
- `.github/scripts/copilot-review/decide.js` skips tier `none` and picks a per-tier token
  (`COPILOT_REVIEW_TOKEN_<TIER>`), which is how a tier becomes a Copilot effort level.
- Apache Magpie triage can filter on the label (`label:aiv:review-none`).

## Failure behavior

The gate cannot fail a change. Configuration errors raise `IllegalArgumentException`, which the CLI
reports as exit 2.

## Extension points

Add a condition only when it is a fact about the diff. Rule hits from other gates are not available
here because gates run independently; supporting them needs an orchestrator post-pass.

## Tests and build

`ReviewRoutingGateTest` covers the default tier, first-match ordering, combined conditions, excluded
and renamed paths, invalid configuration, and message parsing. `MainTest` runs the gate through the CLI
and publishes the label against a fake GitHub API.

```bash
mvn -pl aiv-plugin-review-routing test
mvn -pl aiv-plugin-review-routing verify
```

## Boundary

A tier is a routing decision, not a quality verdict. It says how much model review a change should
get, never whether the change is correct.
