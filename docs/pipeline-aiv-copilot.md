# AIV → Copilot pipeline

Two-stage PR quality flow for this repository.

```text
PR opened / updated
        │
        ▼
┌───────────────────────────────┐
│ Stage 1: AIV Gate (HARD)      │
│ .github/workflows/aiv.yml     │
│ seconds, deterministic        │
│ fail → block merge / CI red   │
└───────────────┬───────────────┘
                │ success only
                ▼
┌───────────────────────────────┐
│ Stage 2: Copilot review       │
│ .github/workflows/            │
│   copilot-review.yml          │
│ advisory only — never fails   │
└───────────────────────────────┘
```

## Why this order

- **Cheap and certain before expensive and fuzzy.** AIV catches objective defects (won't-parse, conflict / edit-artifact markers, unresolved imports) without an LLM.
- **No LLM cost on broken PRs.** Copilot is requested only after AIV succeeds (`workflow_run` on `AIV Gate` success).
- **Invariant:** AIV is the only hard gate. Copilot (and Magpie / any LLM judge) stays advisory.
- Soft AI-slop signals (`severity: warn`) can **label** the PR (`--label-pr-on-advisory`) without failing CI. See [benchmarks/airflow/README.md](../benchmarks/airflow/README.md).

## Prevention layer

[`.github/copilot-instructions.md`](../.github/copilot-instructions.md) steers generation away from the same artifacts AIV blocks.

## Honest scope

AIV does **not** answer "is this PR valuable?" That remains human or advisory review.

A defensible **CI-savings percentage** still needs a labeled true-positive corpus (PRs that would fail expensive CI for parse/conflict-class defects). Until that exists, claim earlier enforcement and reduced false blocks — not a specific savings %.

## Rollout notes

- Do **not** use emoji presence as a hard design signal (false blocks on legitimate code).
- Prefer hard rules on **added lines** against the correct merge-base; soft signals (density, style, TBD/FIXME) should stay advisory or tightly scoped.
