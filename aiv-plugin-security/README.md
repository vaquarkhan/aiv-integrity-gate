# Module: `aiv-plugin-security` (gate `security`)

The security gate is a high-precision added-line tripwire for credential material. It catches recognizable vendor token formats, private-key headers, keyword-bound assigned secrets, and configurable organization patterns before a change reaches a repository.

## Architecture role

Default-off at framework level and enabled by strict presets/configuration. It operates locally on diff evidence, makes no network/model call, and returns hard findings when enabled with `severity: fail`.

## Runtime flow

1. Select configured source/text extensions.
2. Use raw unified diff to obtain added lines; fall back to supplied content when no diff exists.
3. Skip paths matching reviewed `allow_patterns`.
4. Apply enabled vendor patterns.
5. Check password/secret/token assignments with placeholder filtering.
6. Calculate Shannon entropy only for keyword-bound candidate values.
7. Apply trusted custom regex patterns.
8. Return stable findings with source line.

## Built-in rules

`security.aws-key`, `security.github-token`, `security.slack-token`, `security.private-key`, `security.stripe-live`, `security.npm-token`, `security.google-api-key`, `security.openai-key`, `security.anthropic-key`, `security.sendgrid-key`, `security.assigned-secret`, and `security.high-entropy-secret`.

## Configuration

```yaml
gates:
  - id: security
    enabled: true
    severity: fail
    config:
      entropy_threshold: 4.5
      disabled_rules: []
      allow_patterns:
        - "src/test/resources/fixtures/"
      extra_patterns:
        internal-token: "\\bINT-[A-Z0-9]{24}\\b"
```

`file_extensions` / `languages` can narrow selection. Custom pattern ids are prefixed into stable security rule ids.

## Examples

```java
String apiKey = "AKIAABCDEFGHIJKLMNOP";
```

produces `security.aws-key`. A placeholder such as `${API_KEY}` or `example-token` is filtered by candidate logic. Test fixtures should use a narrow path allow rule or a line-level AIV suppression that remains visible in the audit trail.

## Added-line guarantee

When raw diff exists, pre-existing credentials outside new hunks are not re-reported. Identical old/new line text is a known limitation of line-set attribution; use a full secret-history scanner as a backstop.

## Failure behavior

A matched pattern blocks by default. `severity: warn` supports calibration. Disabled rules and allow patterns are policy inputs frozen by the CLI; a PR cannot weaken them in strict mode. Baseline/inline suppression emits `[SUPPRESSED]`.

## Interactions

This gate is intentionally small. Use `external` to run gitleaks, TruffleHog, Semgrep, or organization scanners and fold SARIF into the same verdict. AIV catches changed-line evidence; full scanners cover history and repository-wide context.

## Extension points

Add built-in patterns only when format confidence is high and legitimate counterexamples are covered. Organization-specific patterns belong in config. Entropy must stay keyword-bound to avoid flagging hashes/IDs broadly.

## Tests and build

`SecurityGateTest` covers every vendor rule, entropy/assignment logic, placeholders, paths, added-line scoping, extensions, and findings. `SecurityGateConfigTest` covers disables, allows, custom patterns, and thresholds.

```bash
mvn -pl aiv-plugin-security test
mvn -pl aiv-plugin-security verify
```

## Boundary

This is not a security certification or full secret scanner. It proves that configured high-confidence credential forms were absent from the checked added-line scope. Layer it with repository scanning and credential rotation processes.
