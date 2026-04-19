# Module: `aiv-plugin-design-lucene`

**Role:** **Design compliance gate**—evaluates `.aiv/design-rules.yaml` constraints (forbidden / required substrings, optional keywords) against changed files. For **Java**, the gate can analyze **code surface** (e.g. ignoring string literals in some paths) to reduce false positives from comments or quoted text; other languages use content-based checks as configured.

> **Note:** The historical module name references “Lucene”; runtime design checks do not require embedding Apache Lucene for basic operation.

## SPI

Registers as `QualityGate` with id **`design`**.

## See also

- [`DEVELOPER-CONFIGURATION.md`](../docs/DEVELOPER-CONFIGURATION.md) — `design-rules.yaml` schema

**Author:** Vaquar Khan
