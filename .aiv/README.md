# `.aiv` — AIV configuration (this repository)

This directory holds the **dogfooding** configuration for **AIV Integrity Gate** itself: the same layout consumers use under their repo root.

| File | Role |
|------|------|
| **`config.yaml`** | Which gates run, thresholds, optional `exclude_paths`, `fail_fast`, `skip_allowlist`. |
| **`design-rules.yaml`** | Design gate constraints (forbidden/required patterns, keywords). |

When you add AIV to **your** project, copy this structure to **your** repository root (not inside `aiv-integrity-gate`). See **[docs/TUTORIAL.md](../docs/TUTORIAL.md)** and **[docs/DEVELOPER-CONFIGURATION.md](../docs/DEVELOPER-CONFIGURATION.md)**.

**Author:** Vaquar Khan
