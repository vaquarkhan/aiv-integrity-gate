# Module: `aiv-plugin-syntax`

**Role:** **Syntax gate** - fast parse-validity pre-gate for changed files. Flags Java, Python, YAML, and JSON that do not parse (guaranteed downstream CI failures) with precision-first skips when uncertain.

## SPI

Registers as `QualityGate` with id **`syntax`**.

## Behavior highlights

- Java via JavaParser; skips `#`-leading “Dockerfile.java”.
- Python via `ast.parse` (skip if interpreter missing); only genuine `SyntaxError` blocks.
- YAML/JSON via SnakeYAML `loadAll`; skips templates and `tsconfig*.json`.

## See also

- [`DEVELOPER-CONFIGURATION.md`](../docs/DEVELOPER-CONFIGURATION.md)
- `aiv explain syntax`

**Author:** Vaquar Khan
