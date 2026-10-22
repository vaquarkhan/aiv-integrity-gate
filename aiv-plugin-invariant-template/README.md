# Module: `aiv-plugin-invariant-template` (gate `invariant`)

The invariant gate blocks objective text artifacts that should never enter source control: unresolved conflicts, placeholder implementation, agent edit-paste markers, assistant chatter, provenance trailers pasted into files, and tests that cannot fail.

## Architecture role

Default-on hard gate. `InvariantGate` evaluates changed content, while `AddedLineIndex` maps a unified diff to exact added lines. The gate does not infer who wrote the change.

## Runtime flow

1. Read gate configuration and disabled rule ids.
2. Build an added-line index from `Diff.rawDiff`.
3. Select configured source extensions.
4. Scan conflict markers across the full file.
5. Scan other patterns only on added lines when raw diff is available.
6. Aggregate stable findings and return a blocking result.

When an agent/file-only request has no raw diff, eligible checks use full content and the caller should label the scope accordingly.

## Rules

| Rule | Catches |
|------|---------|
| `invariant.merge-conflict` | `<<<<<<<`, `=======`, `>>>>>>>` conflict blocks |
| `invariant.placeholder` | configured TBD/FIXME/XXX-style placeholders |
| `invariant.ai-edit-artifact` | elision lines, SEARCH/REPLACE markers, assistant edit chatter |
| `invariant.ai-provenance` | AI attribution/trailer text pasted into source |
| `invariant.placeholder-test` | tautologies such as `assertTrue(true)` |
| configured extra rule | team-specific objective artifact regex |

Prose/document files are excluded from agent-edit artifact matching to avoid treating quoted examples as source defects.

## Configuration

```yaml
gates:
  - id: invariant
    enabled: true
    severity: fail
    config:
      placeholder_words: [TBD, FIXME, XXX, HACK]
      disabled_rules: []
      extra_artifact_patterns:
        - "TODO: implement actual logic"
      file_extensions: [.java, .py, .js, .ts, .go]
```

`languages` is accepted as an alias for extension configuration. Rule-family suppressions can target a specific finding.

## Example

```java
// ... existing code unchanged ...
public void process() { }
```

produces an `invariant.ai-edit-artifact` finding on the added line. A pre-existing copy outside added hunks is not re-flagged.

## Failure semantics

Confirmed invariant findings are blocking by default. Configure `severity: warn` only for a calibration period. Baselines and line/file suppressions are applied by `aiv-core` and produce `[SUPPRESSED]` evidence.

A conflict marker is whole-file scoped because any unresolved conflict makes the file unsafe regardless of hunk attribution.

## Interactions

- `syntax` catches structural parse failures after markers corrupt syntax.
- `test-adequacy` finds assertion-free functions; invariant catches explicit tautologies.
- `rules` handles project-specific patterns that should not become Java code.
- `security` handles credential material on added lines.

## Extending patterns

Prefer `extra_artifact_patterns` for team-local patterns. Add a built-in rule only when it is objective across projects, has a stable rule id, and can be tested against legitimate counterexamples. Keep regexes bounded and added-line scoped.

## Tests and build

`InvariantGateTest`, `InvariantGateConfigTest`, and added-line index tests cover every marker, extension/path behavior, disabled/extra rules, diff scoping, prose exclusions, and findings.

```bash
mvn -pl aiv-plugin-invariant-template test
mvn -pl aiv-plugin-invariant-template verify
```

## Boundary

The gate identifies defined artifacts, not low quality in general. Absence of a marker does not prove the implementation is complete or correct; it proves only that these objective failure forms are absent from the checked scope.
