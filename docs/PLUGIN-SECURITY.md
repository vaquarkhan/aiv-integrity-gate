# Configure the security gate

The `security` gate is a high-confidence added-line credential tripwire. It is not a full repository/history secret scanner; use the `external` gate to include one.

## Enable

```yaml
gates:
  - id: security
    enabled: true
    severity: fail
    config:
      entropy_threshold: 4.5
      allow_patterns: ["src/test/resources/fixtures/"]
      disabled_rules: []
      extra_patterns:
        internal-token: "\\bINT-[A-Z0-9]{24}\\b"
```

## Built-in evidence

Vendor token/key forms, private-key headers, password/secret/token assignments, and keyword-bound high-entropy candidates. Placeholders and reviewed fixture paths can be excluded.

## Added-line scope

With raw diff, only added lines are scanned. File-only agent requests without raw diff evaluate the supplied content. Keep full repository/history scanning in a dedicated tool.

## Combine with a full scanner

```yaml
  - id: external
    enabled: true
    severity: fail
    config:
      tools:
        - id: gitleaks
          command: ["gitleaks", "detect", "--report-format", "sarif", "--report-path", "{sarif_file}"]
          sarif: file
          on_missing: fail
```

The external result receives stable `external.gitleaks.*` ids and joins the same JSON/SARIF/GitHub/audit receipt.

## Test

Add a fake known-format key on a branch, run against the base ref, confirm the expected `security.*` rule/file/line, remove it, and confirm the finding clears.

Full algorithm/config/test details: [aiv-plugin-security README](../aiv-plugin-security/README.md). Profile advice: [MODULE-ADOPTION-GUIDE.md](MODULE-ADOPTION-GUIDE.md).

**Author:** Viquar Khan
