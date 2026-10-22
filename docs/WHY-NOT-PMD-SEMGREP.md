# AIV with compilers, PMD, Semgrep, Checkstyle, and secret scanners

AIV does not replace mature analyzers. It creates a diff-scoped evidence boundary that runs before or around them, protects the policy used to judge a change, and folds trusted SARIF into one verdict.

![AIV complete architecture](images/aiv-architecture.png)

**Figure 1: External analyzers are inputs to the evidence engine.** AIV can run its own bounded integrity checks and ingest PMD/Semgrep/gitleaks/ruff/other SARIF through the `external` gate. The repository receives one severity-aware report without asking AIV to reimplement every analyzer.

## Different jobs

| Tool type | Primary job | AIV relationship |
|-----------|-------------|------------------|
| compiler/type checker | language/build correctness | run through CI or `external`; AIV catches earlier diff integrity |
| PMD/Checkstyle | mature Java bug/style rules | keep existing rules; optionally ingest report |
| Semgrep | security/custom static patterns | use for deep rules; ingest SARIF |
| secret scanner | repository/history credentials | keep as full backstop; AIV security is an added-line tripwire |
| dependency/SBOM scanner | vulnerability/license/provenance | keep; AIV checks declaration/import/existence evidence |
| LLM reviewer | contextual design/intent judgement | keep advisory; AIV supplies reproducible facts |
| orchestrator/policy engine | workflow/allow-deny routing | consume AIV verdict/facts |

## What AIV contributes

- one pinned/frozen repository policy for the run,
- changed-file/added-line evidence,
- strict unverified states rather than false confirmation,
- stable rule ids and severity across built-in/external findings,
- JSON, SARIF, GitHub, audit, and report surfaces,
- agent/AI-DLC schema-v2 integration.

## Example: fold Semgrep SARIF into AIV

```yaml
gates:
  - id: external
    enabled: true
    severity: fail
    config:
      tools:
        - id: semgrep
          command: ["semgrep", "--sarif", "--output", "{sarif_file}", "{files}"]
          sarif: file
          min_level: warning
          on_missing: fail
```

Pin/install the analyzer in CI. AIV bounds execution, reads SARIF, filters findings to changed files, prefixes stable ids (`external.semgrep.<rule>`), preserves severity, and includes them in the same receipt.

## When to use only the existing tool

Use the existing tool alone when its native report/policy already gives the exact workflow you need and you do not need AIV's other gate/evidence/trust surfaces.

## When to add AIV

Add AIV when you need several evidence classes under one protected change policy, a machine contract for agents/lifecycle gates, or one audit/report path across built-in and external checks.

## Recommended order

```text
AIV local objective checks
  -> trusted compiler/SAST/secret/dependency analyzers through CI/external
  -> advisory LLM/human judgement
  -> merge/deploy decision
```

See [MODULE-ADOPTION-GUIDE.md](MODULE-ADOPTION-GUIDE.md) and the detailed [`external` module guide](../aiv-plugin-external/README.md).

**Author:** Viquar Khan
