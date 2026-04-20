# Module: `aiv-core`

**Role:** **Orchestration** - loads configuration, obtains a `Diff` from the `DiffProvider`, runs all enabled `QualityGate` implementations, aggregates results, and publishes via `ReportPublisher`.

## Responsibilities

- Filter changed files using `exclude_paths` from config.
- Honor **`fail_fast`** (stop at first failing gate) vs run-all-gates mode.
- Evaluate **skip** directives on the diff (coordinated with `aiv-adapter-git`).
- Invoke SPI-loaded gates in a defined order.

## Main entry type

- `io.github.vaquarkhan.aiv.core.Orchestrator` - primary API used by `aiv-cli`.

## See also

- [`aiv-api`](../aiv-api/README.md) - ports and models
- [`aiv-cli`](../aiv-cli/README.md) - command-line entry

**Author:** Vaquar Khan
