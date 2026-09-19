# syntax

Fast parse-validity pre-gate. Flags changed files that do not parse — a guaranteed downstream CI failure caught in milliseconds.

## Rules

- `syntax.parse` — file content failed a language-aware parse check.

## Languages

- **Java** — JavaParser (JAVA_17). Skips `.java` files whose first non-blank line starts with `#` (e.g. `Dockerfile.java`).
- **Python** — `ast.parse` via a local interpreter (`python`, override with `-Daiv.python.command=...`). Missing interpreter → skip (pass).
- **YAML / JSON** — SnakeYAML `loadAll` (multi-doc OK). Skips Helm/Jinja templates (`{{` / `{%`). Skips `tsconfig*.json` (JSONC).

## Design

Precision-first: when uncertain or tooling is unavailable, the gate **passes** rather than guess-blocking.
