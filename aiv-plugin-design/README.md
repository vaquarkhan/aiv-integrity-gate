# Module: `aiv-plugin-design`

**Role:** **Design compliance gate** - evaluates `.aiv/design-rules.yaml` constraints (forbidden / required patterns, optional keywords) against changed files. For **Java**, the gate analyzes a **code surface** that strips comments and string literals to reduce false positives from documentation or quoted text; other languages use content-based checks as configured.

## SPI

Registers as `QualityGate` with id **`design`**.

## See also

- [`DEVELOPER-CONFIGURATION.md`](../docs/DEVELOPER-CONFIGURATION.md) - `design-rules.yaml` schema

**Author:** Vaquar Khan
