# syntax

Fast parse-validity pre-gate. Flags changed files that do not parse — a guaranteed downstream CI failure caught in milliseconds.

## Rules

- `syntax.parse` — file content failed a language-aware parse check.

## Languages

- **Java** — JavaParser (JAVA_17). Skips `.java` files whose first non-blank line starts with `#` (e.g. `Dockerfile.java`).
- **Python** — `ast.parse` via a local interpreter (`python`, override with `-Daiv.python.command=...`). Missing interpreter → skip (pass).
- **JavaScript** — `node --check` (`.js` / `.mjs` / `.cjs`; override `-Daiv.node.command=...`). Missing Node → skip.
- **TypeScript** — `node --experimental-strip-types --check` when the runtime supports it; otherwise skip. **JSX/TSX skipped** (high FP without a transform).
- **Go** — `gofmt` on stdin (`-Daiv.gofmt.command=...`). Missing gofmt → skip.
- **YAML / JSON** — SnakeYAML `loadAll` (multi-doc OK). Skips Helm/Jinja templates (`{{` / `{%`). Skips `tsconfig*.json` (JSONC).

## Design

Precision-first: when uncertain or tooling is unavailable, the gate **passes** rather than guess-blocking.
