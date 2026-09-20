# Strategy (internal)

**What it is:** cheap, local check on the diff for objectively broken paste/parse/imports/secrets - leave it on forever.

**Headline (defensible):** catch the PR that cannot pass (won't-parse, conflict/paste junk, fake tests, bad imports) before CI burns a long matrix. Not "we catch AI slop."

Public status: [README Roadmap](../../README.md#roadmap).

Review filter: [DUE-DILIGENCE.md](DUE-DILIGENCE.md) · claims: [CLAIMS.md](CLAIMS.md).

## Near-term build order

1. Public demo repo (seeded FAIL + PASS)
2. `// aiv-disable-next-line`
3. Docker (then brew/npx if needed)
4. Config presets + CI snippets beyond GitHub
5. Secrets heuristics only with FP discipline
6. JS/TS syntax spike (optional tree-sitter)
7. MCP adapter (`InMemoryDiffProvider`) after install story works
8. Opt-in mechanical `--fix` last (trust-sensitive)

## Explicitly not near-term

- CI-minutes-saved badges or dollar claims
- Org rollup / SLSA provenance product
- Magpie skill packaging
