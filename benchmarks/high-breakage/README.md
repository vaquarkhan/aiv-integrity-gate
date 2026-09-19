# High-breakage demo corpus

Intentional **agent-dump style** breakage for demos (not Airflow). Same fixtures as [../true-positive](../true-positive/) plus a packed "PR" folder.

## Quick demo

```powershell
cd benchmarks/high-breakage
./run-demo.ps1
```

Expect **FAIL** with findings from syntax / invariant / security. That is success for the demo.

## Why this exists

Mature repos rarely contain these defects. This corpus shows AIV's value where breakage is present: leave-on tripwire, seconds, no LLM.
