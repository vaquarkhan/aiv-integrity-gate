# High-breakage demo corpus

Intentional **agent-dump style** breakage for demos (not Airflow). Same fixtures as [../true-positive](../true-positive/) plus a packed "PR" folder.

## Quick demo

```powershell
cd benchmarks/high-breakage
./run-demo.ps1
```

Expect **FAIL** with findings from syntax / invariant / security. That is success for the demo.

## Why this exists

Mature repositories rarely contain these objective defects. This corpus proves the configured rules fire on known breakage and remain suitable as a local/CI tripwire; it does not establish a time-savings or field precision percentage.
