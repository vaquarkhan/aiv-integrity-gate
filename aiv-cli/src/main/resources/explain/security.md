# Security gate

Diff-scoped tripwire for **high-confidence secrets on added lines**:

- AWS access key ids (`AKIA…` / `ASIA…`)
- GitHub tokens (`ghp_` / `github_pat_` / …)
- Slack tokens (`xox…-`)
- PEM/OpenSSH private key headers
- Stripe live (`sk_live_`), npm (`npm_`), Google (`AIza…`)
- OpenAI project (`sk-proj-`), Anthropic (`sk-ant-`), SendGrid (`SG.…`)
- Obvious `api_key = "…"` assignments
- Keyword-bound high-entropy `password` / `secret` assignments (Shannon ≥ 4.5; placeholders skipped)

**Off by default.** Enable:

```yaml
gates:
  - id: security
    enabled: true
```

Does not replace a full secrets scanner; it is a forever-on Class A check for accidental paste into a PR.
Precision-first: no bare high-entropy scan of every string literal.
