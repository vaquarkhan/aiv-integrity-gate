# Security plugin (`aiv-plugin-security`)

Shipped as an **optional** gate (`id: security`, **off by default**).

Scans **added lines** for high-confidence credential leaks (AWS key ids, GitHub/Slack tokens, private key headers, obvious `api_key = "..."` assignments).

```yaml
gates:
  - id: security
    enabled: true
```

```bash
java -jar aiv-cli.jar explain security
```

Not a replacement for enterprise secret scanners; a forever-on tripwire for accidental paste into a PR.
