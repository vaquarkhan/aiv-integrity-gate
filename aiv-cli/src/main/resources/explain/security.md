# Security gate

Diff-scoped tripwire for **high-confidence secrets on added lines**:

- AWS access key ids (`AKIA…` / `ASIA…`)
- GitHub tokens (`ghp_` / `gho_` / …)
- Slack tokens (`xox…-`)
- PEM/OpenSSH private key headers
- Obvious `api_key = "…"` / `secret_key = "…"` assignments

**Off by default.** Enable:

```yaml
gates:
  - id: security
    enabled: true
```

Does not replace a full secrets scanner; it is a forever-on Class A check for accidental paste into a PR.
