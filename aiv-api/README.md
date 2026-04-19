# Module: `aiv-api`

**Role:** Public **API surface** for AIV—interfaces, configuration models, and result types shared by `aiv-core`, plugins, and adapters.

## Key packages

| Package | Contents |
|---------|----------|
| `io.github.vaquarkhan.aiv.model` | `AIVConfig`, `AIVResult`, `GateResult`, `Diff`, `ChangedFile`, etc. |
| `io.github.vaquarkhan.aiv.port` | **Ports**: `QualityGate`, `DiffProvider`, `ConfigProvider`, `ReportPublisher` |

Plugins implement `QualityGate` and are discovered via **Java SPI** (`META-INF/services`).

## Dependencies

Minimal—this module is meant to stay lightweight so third-party gates can depend on it without pulling adapters.

## See also

- [`aiv-core`](../aiv-core/README.md) — orchestration
- [`DEVELOPER-CONFIGURATION.md`](../docs/DEVELOPER-CONFIGURATION.md) — YAML mapping to `AIVConfig`

**Author:** Vaquar Khan
