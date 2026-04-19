# Module: `aiv-adapter-git`

**Role:** **Git-backed `DiffProvider`**—runs `git diff`, `git show`, and related commands to build a `Diff` model (changed files, raw patch, line stats, optional per-file net LOC, author info, skip directive parsing).

## Behavior highlights

- **Ref validation** to reduce injection risks from malformed `--diff` / `--head` values.
- **Timeouts** and output size caps (configurable via system properties such as `aiv.git.timeout.seconds`).
- **Path sanitization** and workspace boundary checks before reading file contents.
- **Skip directive:** anchored line match on the **latest commit only** (see core orchestrator for interaction with `skip_allowlist`).

## See also

- [`aiv-api`](../aiv-api/README.md) — `DiffProvider` port
- [`DEVELOPER-CONFIGURATION.md`](../docs/DEVELOPER-CONFIGURATION.md) — CLI refs

**Author:** Vaquar Khan
