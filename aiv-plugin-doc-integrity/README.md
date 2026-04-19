# Module: `aiv-plugin-doc-integrity`

**Role:** **Doc integrity gate** - validates documentation files in the diff (Markdown, text, reStructuredText, common agent/guide filenames): broken links, fabricated paths, cross-reference patterns, optional command completeness checks, and rules loaded from `.aiv/doc-rules.yaml`.

## SPI

Registers as `QualityGate` with id **`doc-integrity`**. Often used together with **`--include-doc-checks`** or `auto: true` in config so runs trigger only when docs change.

## See also

- [`DEVELOPER-CONFIGURATION.md`](../docs/DEVELOPER-CONFIGURATION.md) - doc rules and `auto` flag

**Author:** Vaquar Khan
