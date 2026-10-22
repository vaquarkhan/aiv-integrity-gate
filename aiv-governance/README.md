# Module: `aiv-governance`

`aiv-governance` is a library for deterministic concern routing: decide whether an automated delegate may handle a concern, whether to use a fallback, or whether human attention is required, while recording the decision.

## Architecture role

The module sits above evidence producers and below an orchestrator/UI:

```text
concern + delegate availability/result + policy
              |
              v
       GovernanceRouter
              |
              v
delegate / fallback / human-required + GovernanceReport + audit record
```

It is not wired into the main CLI gate flow. Hosts opt in as a library.

## Core concepts

| Type | Purpose |
|------|---------|
| `Concern` | named work/risk item to resolve |
| `ConcernDelegate` | optional automated implementation |
| `ConcernOutcome` | status, mode, message/evidence |
| `GovernanceRouter` | deterministic delegate-or-fallback decision logic |
| `GovernanceReport` | ordered outcomes for the host/auditor |

Modes distinguish delegated work, deterministic fallback, and unresolved/human-required paths. Stable statuses let a host route without interpreting free text.

## Typical flow

1. Host defines concerns and available delegates.
2. Router evaluates each concern in stable order.
3. A delegate can return supported/unsupported/success/failure evidence.
4. Unsupported/unavailable delegates move to configured deterministic fallback.
5. Unresolved or failed high-stakes concerns become human-required.
6. Host consumes the report and may append it through `aiv-audit`.

## Example

```java
GovernanceRouter router = new GovernanceRouter(...);
GovernanceReport report = router.evaluate(concerns);
for (ConcernOutcome outcome : report.outcomes()) {
    // route, display, or record outcome
}
```

See the Java types/tests for the exact constructor and callback contracts used by the current version.

## Determinism and trust

The router does not call an LLM or remote service by itself. Determinism depends on supplied delegates/fallbacks. A host should label external/model delegates and keep them out of an automatic blocking decision unless their evidence is independently verified.

## Integration opportunities

- AI-DLC or another SDLC orchestrator can map concern outcomes to human routing.
- AIV gate results can supply deterministic evidence for concerns.
- Aegis-style gateways can use outcomes for allow/approval/deny handling.
- `HashChainAudit` can preserve a routing decision trail.

## Failure behavior

The library distinguishes unavailable/unsupported from failed. It does not silently claim success when no delegate/fallback produced evidence. Hosts remain responsible for mapping statuses to workflow blocking/approval.

## Extension points

Implement new delegates/fallbacks behind the existing interfaces. Preserve stable concern ids and ordered reports. Avoid embedding platform-specific transport/UI behavior in the router.

## Tests and build

`GovernanceTest` covers delegate success/failure/unavailable paths, fallback selection, human-required outcomes, ordering/report behavior, and audit integration.

```bash
mvn -pl aiv-governance test
mvn -pl aiv-governance verify
```

## Boundary

This module is a routing primitive, not a full control plane, durable workflow engine, identity system, or approval interface. It supplies deterministic decisions that a host orchestrator can act on.
