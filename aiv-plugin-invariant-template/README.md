# Module: `aiv-plugin-invariant-template`

**Role:** **Invariant gate** - hard checks for merge-conflict markers, TBD/FIXME/XXX placeholders, and **AI edit-artifacts** (elision / assistant chatter / SEARCH-REPLACE) in code files only.

## SPI

Registers as `QualityGate` with id **`invariant`**.

## Production use

Often **disabled** in starter `.aiv/config.yaml` until you accept placeholder scanning on whole changed files. Enable when you want these hard blocks. Docs/prose are not flagged by the AI edit-artifact rule.

## See also

- [`aiv-api`](../aiv-api/README.md) - `QualityGate`
- `aiv explain invariant`

**Author:** Vaquar Khan
