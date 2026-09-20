# Due diligence on external product review (2026-09)

Reviewed the pasted Product Review / RICE roadmap / competitive analysis / MCP sketch against repo evidence and [CLAIMS.md](CLAIMS.md).

## Agree (keep)

| Point | Why |
|-------|-----|
| Lead with "guaranteed-to-fail PR before CI matrix", not "AI slop" | Matches Airflow evidence: objective marker-style hits are rare; syntax/conflict/fake-test are always defensible |
| Empty quadrant: deterministic + diff-scoped + integrity | Still true vs PMD/Semgrep (whole-repo) and CodeRabbit/Copilot (LLM) |
| Public demo repo first | Highest leverage, already on Planned |
| `// aiv-disable-next-line` | Table stakes; low effort |
| Frictionless install (Docker / wrappers) | Real barrier for non-Java users |
| MCP later via `DiffProvider` + JSON findings | Fits hexagonal design; no core rewrite |
| Complementary to SAST and AI review | Already in README / pipeline doc |

## Reject or defer (do not put on public roadmap as commitments)

| Item | Why |
|------|-----|
| Badge / metric "CI minutes saved" | Violates claims: no estimated CI-cost savings on public surfaces |
| Org rollup service / SLSA attestation as near-term | Pre-traction; enterprise theater before users |
| Auto-fix as a headline | Precision-first trust risk; only as opt-in later, mechanical classes only |
| Tree-sitter as hard P0 for "best product" | High effort; syntax already covers 4 langs; spike JS/TS first, don't promise parity |
| Competing as "AI-slop catcher" brand | Own data and CLAIMS forbid unqualified slop claims |

## Sequence we will actually follow

1. Public demo repo (FAIL + PASS seeded PRs)
2. Inline suppress + install wrappers (Docker first)
3. Config presets + non-GitHub CI snippets (docs)
4. Secrets heuristics only with TP coverage
5. Syntax breadth spike (JS/TS) if demand shows
6. MCP adapter after packaging exists
7. Opt-in mechanical `--fix` only after suppress + demo exist

Public list: [README Roadmap](../../README.md#roadmap).
