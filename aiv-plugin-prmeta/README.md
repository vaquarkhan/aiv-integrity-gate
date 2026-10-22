# Module: `aiv-plugin-prmeta` (gate `pr-metadata`)

The PR metadata gate verifies that a pull request provides review context: a useful title, a substantive body, no untouched template scaffolding, and optionally an AI-assistance disclosure marker.

## Architecture role

Default-off policy gate. It consumes metadata supplied through `AIVContext` or environment and returns deterministic findings. It never calls GitHub itself; publication belongs to `aiv-adapter-github`.

## Input resolution

Priority order:

1. context metadata: `pr_title`, `pr_body`, `pr_template`,
2. environment: `AIV_PR_TITLE`, `AIV_PR_BODY`, `AIV_PR_TEMPLATE`.

The composite Action maps pull-request event values into these variables without interpolating PR text into shell commands.

## Runtime flow

1. Resolve title/body/template.
2. If metadata is absent (for example pre-commit), pass without pretending a PR was checked.
3. Reject empty, generic, issue-only, too-short, or conventional-prefix-only titles.
4. Reject empty, repeated-title, or untouched-template bodies.
5. If configured, require one disclosure marker.
6. Return stable findings with `(pull request)` scope.

## Configuration

```yaml
gates:
  - id: pr-metadata
    enabled: true
    severity: fail
    config:
      min_title_length: 12
      min_body_length: 40
      require_disclosure: false
      disclosure_markers:
        - "AI-assisted"
        - "Generated with"
```

Exact supported keys/defaults are in [DEVELOPER-CONFIGURATION.md](../docs/DEVELOPER-CONFIGURATION.md).

## Findings

Rules distinguish title and body problems and missing required disclosure. Stable ids allow baselines/publication filters without parsing prose.

## Example

Title `Fix #123` with body `Fix #123` is rejected as issue-only/repeated context. A useful PR states what changed, why, validation performed, and any relevant scope/risk.

## Failure behavior

Configured failures block by default; `severity: warn` is useful while introducing templates. Missing metadata on a local run passes because the gate has no PR to evaluate. CI wiring should supply all fields, and teams may add a workflow assertion if absence must fail.

## Disclosure semantics

Disclosure checks presence only. AIV does not infer whether AI wrote code, whether the disclosure is truthful, or authorship. This keeps the gate deterministic and avoids classifier claims.

## Interactions

- The Action supplies metadata.
- GitHub publishers consume the resulting findings.
- `aiv verify` can pass PR metadata through request/context integrations.
- Immutable config protects title/body/disclosure policy from same-PR changes.

## Extension points

Add a metadata rule only when it is mechanically testable. Keep organization-specific wording in config. If adding a required field, define missing-input behavior explicitly and add local/CI tests.

## Tests and build

`PrMetadataGateTest` covers absent metadata, title classes, body/template checks, disclosure, environment fallback, configuration, rule ids, and ServiceLoader.

```bash
mvn -pl aiv-plugin-prmeta test
mvn -pl aiv-plugin-prmeta verify
```

## Boundary

This gate proves review metadata meets configured structural requirements. It does not prove the description matches implementation; that requires human review or an advisory decision-model check.
