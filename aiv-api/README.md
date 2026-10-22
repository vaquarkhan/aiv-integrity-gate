# Module: `aiv-api`

`aiv-api` is the dependency-light contract layer shared by every AIV module. It contains the immutable data model, extension ports, and utility classes used by gates, adapters, the CLI, audit/report components, and integrations.

## Architecture role

```text
Git / memory adapters -> Diff -> AIVContext -> QualityGate -> GateResult -> publishers
                                  |
                                  +-> AIVConfig
```

The API module owns contracts only. It does not read Git, files, environment variables, HTTP endpoints, or model services. Implementations belong in adapters and plugins.

## Core model

| Class | Purpose |
|-------|---------|
| `ChangedFile` | Normalized path, `ADDED` / `MODIFIED` / `DELETED`, and full content |
| `Diff` | Base/head refs, changed files, raw unified diff, LOC, author/signature/skip metadata, warnings |
| `AIVConfig` | Ordered gate declarations plus global configuration |
| `AIVConfig.GateConfig` | Gate id, enabled state, severity, and gate-specific map |
| `AIVContext` | Source workspace, immutable policy workspace, diff, config, metadata |
| `Finding` | Stable rule id, file, line/column range, and message |
| `GateResult` | Per-gate pass/fail, CI-blocking flag, message, structured findings |
| `AIVResult` | Aggregated verdict, ordered gate results, and visible notices |

### Two workspaces in `AIVContext`

`getWorkspace()` points at the source tree being evaluated. `getPolicyWorkspace()` points at the frozen policy snapshot for this run. Policy-bearing gates must read rules and baselines through `getPolicyWorkspace()`, never through the live source workspace. This prevents a concurrent process from changing policy after trust verification.

Existing constructors set both roots to the same path for library compatibility. The five-argument constructor lets the CLI provide a separate immutable policy root. `withMetadata()` preserves both roots.

## Extension ports

```java
public interface QualityGate {
    String getId();
    GateResult evaluate(AIVContext context);
}

public interface DiffProvider {
    Diff getDiff(Path workspace, String baseRef, String headRef);
}

public interface ConfigProvider {
    AIVConfig getConfig(Path workspace);
}

public interface ReportPublisher {
    void publish(AIVResult result);
}
```

A gate returns `GateResult.pass(id)`, `GateResult.fail(...)` for objective blockers, or `GateResult.advisory(...)` for review signals. Attach `Finding` objects whenever a file/line can be identified; JSON, SARIF, Checks, comments, baselines, and suppressions depend on stable finding identity.

## Strict JSON utility

`JsonLite` is the shared dependency-free RFC 8259 parser/writer used at agent and integration boundaries. It accepts only JSON whitespace, rejects raw control characters, enforces the exact number grammar, handles JSON escapes, and produces ordered maps/lists. `MemoryDiffProvider` and `aiv verify` use it, so malformed agent input fails closed.

```java
Object parsed = JsonLite.parse("{\"files\":[]}");
String json = JsonLite.write(Map.of("passed", true));
```

## Utility classes

- `FileExtensions`: normalize configured extension/language lists and match paths.
- `PathFilter`: apply repository-wide exclusion patterns consistently.
- `JsonLite`: strict JSON parsing, path lookup, list/string helpers, serialization.

## Contract rules

1. Model objects are immutable or expose immutable copies.
2. Rule ids use `<gate>.<rule>` and remain stable across releases.
3. Paths are repository-relative and normalized by adapters.
4. A passing gate may carry review evidence, such as `syntax.unverified`; consumers must not discard pass-with-findings results.
5. `blocksCi()` is authoritative for severity, not message text.
6. Policy files are read through `AIVContext.getPolicyWorkspace()`.

## Example gate

```java
public final class NoTodoGate implements QualityGate {
    public String getId() { return "no-todo"; }
    public GateResult evaluate(AIVContext context) {
        for (ChangedFile file : context.getDiff().getChangedFiles()) {
            if (file.getContent().contains("TODO")) {
                Finding f = Finding.atLine("no-todo.marker", file.getPath(), 1, "TODO remains");
                return GateResult.fail(getId(), "TODO remains", List.of(f));
            }
        }
        return GateResult.pass(getId());
    }
}
```

Register the implementation through `META-INF/services/io.github.vaquarkhan.aiv.port.QualityGate` in its plugin module.

## Dependencies and module interactions

The module intentionally has no dependency on GitHub, Maven execution, registry clients, or plugin implementations. Every gate depends on `aiv-api`; `aiv-core` orchestrates these contracts; adapters create/consume them; `aiv-cli` composes the runtime.

## Tests and build

Tests cover every model constructor/getter, immutable-copy behavior, null handling, helper method, path/extension behavior, and strict JSON grammar.

```bash
mvn -pl aiv-api test
mvn -pl aiv-api verify
```

`verify` enforces the configured JaCoCo line-coverage gate. Add API tests whenever a contract branch changes because every downstream module compiles against this surface.

## Boundaries

`aiv-api` defines evidence structures but does not decide which gates run, where policy comes from, or how results are published. Those responsibilities remain in `aiv-core`, `aiv-cli`, adapters, and plugins.
