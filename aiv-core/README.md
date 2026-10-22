# Module: `aiv-core`

`aiv-core` is the deterministic orchestration kernel. It obtains one diff and one resolved configuration, builds an immutable gate context, runs ServiceLoader gates in order, applies severity and suppression policy, aggregates notices, publishes the result, and returns the CI exit decision.

## Runtime flow

```text
DiffProvider + ConfigProvider
          |
          v
filter excluded paths -> AIVContext(source workspace + frozen policy workspace)
          |
          v
ServiceLoader gates -> severity -> baseline -> inline suppression
          |
          v
AIVResult -> ReportPublisher -> exit 0/1
```

## `Orchestrator`

The constructor accepts a `DiffProvider`, `ConfigProvider`, `ReportPublisher`, and optionally a separate policy workspace. The three-argument constructor keeps library compatibility by using the source workspace for policy. The CLI always supplies a frozen policy snapshot.

```java
Orchestrator orchestrator = new Orchestrator(diffProvider, configProvider, publisher, policySnapshot);
int exit = orchestrator.run(workspace, "origin/main", "HEAD", false);
```

### Evaluation sequence

1. Resolve the diff and config once.
2. Evaluate signed-author and skip policy warnings.
3. Honor `/aiv skip` only for an allow-listed author and a good commit signature.
4. Remove globally excluded paths while retaining policy-change visibility.
5. Build `AIVContext` with source and immutable policy roots.
6. Discover `QualityGate` implementations with `ServiceLoader`.
7. Skip disabled/default-off gates and apply doc auto-selection.
8. Evaluate each gate.
9. Convert configured `severity: warn` blockers to advisory results.
10. Apply baseline suppression from the frozen policy workspace.
11. Apply source-level `aiv-disable` directives.
12. Record visible suppression, config-change, and skip notices.
13. Publish one `AIVResult`; return 0 for pass, 1 for blocking failure.

## Default gate policy

Default-off gates are `doc-integrity`, `security`, `license-header`, `pr-metadata`, `external`, `rules`, and `jev`. Other discovered gates run unless disabled. Presets make every important choice explicit; production repositories should do the same.

## Policy integrity

When the CLI enables `protect_policy_paths`, any changed path under `.aiv/` adds a blocking `policy-integrity.changed` finding. Strict Action/CI mode also proves the complete live policy tree matches the trusted base commit before orchestration. Policy changes are evaluated through a separately approved run.

## Human override

`/aiv skip` is default-deny. It is honored only when:

- the latest commit contains a standalone `aiv skip` or `/aiv skip` line,
- global `skip_allowlist` is nonempty,
- author email matches it case-insensitively,
- Git reports signature status `G`.

An accepted override emits `[SKIP-HONORED]` and evaluates no gate. Any failed condition emits `[SKIP-IGNORED]` and the normal gates run.

## Baselines

A baseline line is:

```text
rule_id|file
rule_id|file|optional message substring
```

The baseline is loaded from `AIVContext.getPolicyWorkspace()`, so the exact frozen bytes used for suppression are the bytes bound into verify evidence. Missing/malformed configured baseline files fail the run. Removed findings produce `[SUPPRESSED]`; suppression is never silent.

## Inline suppression

Supported directives:

```java
// aiv-disable-next-line security.assigned-secret
String fixture = "...";

String fixture = "..."; // aiv-disable-line security.assigned-secret

// aiv-disable rules.team.*
```

A file-level directive must name a rule/family; bare or wildcard-wide suppression is refused. Line directives may target the current/next line. Baseline filtering runs before inline filtering.

## Severity and fail-fast

- `GateResult.fail` blocks unless gate config says `severity: warn`.
- Intrinsically advisory results remain advisory even if config says fail.
- `fail_fast: true` stops after the first blocking gate.
- Doctor mode reports real results/notices but returns exit 0.

## Notices

| Notice | Meaning |
|--------|---------|
| `[CONFIG-CHANGE]` | the diff touches `.aiv/` |
| `[SKIP-HONORED]` | signed allow-listed override accepted |
| `[SKIP-IGNORED]` | override attempt rejected |
| `[SUPPRESSED]` | baseline or inline directive removed findings |
| `[DOCTOR]` | informational run |

Provider warnings such as `[POLICY-FALLBACK]` are added by the CLI around the core result.

## Extension points

Add a gate by implementing `QualityGate` in a separate module and registering it with ServiceLoader. Keep gate logic independent of GitHub/CLI concerns. Return stable structured findings. Put repository policy files under `.aiv/` and read them through the policy workspace.

Custom embedders can supply their own diff/config/publisher implementations without depending on the CLI.

## Tests and build

- `OrchestratorTest`: ordering, filtering, policy-integrity, skip, fail-fast, doctor, defaults.
- `OrchestratorSeverityTest`: fail/warn conversion.
- `OrchestratorAuditNoticesTest`: skip/suppression evidence.
- `BaselineFilterTest`: baseline parsing/matching.
- `InlineDisableFilterTest`: directive scope and families.
- `testgates/`: ServiceLoader fixtures.

```bash
mvn -pl aiv-core test
mvn -pl aiv-core verify
```

## Boundaries

The core does not execute Git, load YAML, call registries/models, or publish to GitHub. It trusts the supplied providers and the frozen policy root. The CLI is responsible for establishing that trust boundary.
