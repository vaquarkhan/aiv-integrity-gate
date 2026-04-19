# Module: `aiv-plugin-invariant-template`

**Role:** **Placeholder invariant gate**—ships as a **template** for property-based or custom invariants. The default implementation **passes** so teams can fork the pattern without blocking CI.

## SPI

Registers as `QualityGate` with id **`invariant`**.

## Production use

Disable in `.aiv/config.yaml` until you replace it with real checks, or keep it enabled with the understanding it is a no-op pass-through.

## See also

- [`aiv-api`](../aiv-api/README.md) — `QualityGate`

**Author:** Vaquar Khan
