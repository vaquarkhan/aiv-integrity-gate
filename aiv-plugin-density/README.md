# Module: `aiv-plugin-density`

**Role:** **Density gate** - flags low-signal or boilerplate-heavy changes using:

- **Logic Density Ratio (LDR)** for **Java** (control flow vs structural noise).
- **Shannon entropy** and related heuristics for configured file types.
- Optional **refactor** and **trusted author** exemptions (see `DEVELOPER-CONFIGURATION.md`).

## SPI

Registers as `QualityGate` with id **`density`**.

## Tuning

Typical YAML keys under the gate: `ldr_threshold`, `entropy_threshold`, `refactor_net_loc_threshold`, `trusted_authors`, `file_extensions`, `languages`.

## See also

- [`DEVELOPER-CONFIGURATION.md`](../docs/DEVELOPER-CONFIGURATION.md) - thresholds and defaults

**Author:** Vaquar Khan
