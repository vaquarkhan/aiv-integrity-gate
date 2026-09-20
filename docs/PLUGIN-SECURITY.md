# Security plugin (`aiv-plugin-security`)

Shipped as an **optional** gate (`id: security`, **off by default**).

Scans **added lines** for high-confidence credential leaks:

- Vendor prefixes (AWS, GitHub, Slack, Stripe, npm, Google, OpenAI, Anthropic, SendGrid)
- Private key PEM headers
- Obvious `api_key = "…"` assignments
- Keyword-bound high-entropy password/secret values (not every random string)

```yaml
gates:
  - id: security
    enabled: true
```

```bash
java -jar aiv-cli.jar explain security
```

Not a replacement for enterprise secret scanners; a forever-on tripwire for accidental paste into a PR.
