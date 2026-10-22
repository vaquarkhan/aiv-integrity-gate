# Gate: `external`
## Why it exists
Runs analyzers you already trust (ruff, semgrep, gitleaks, checkstyle, anything that writes SARIF) **inside the AIV verdict**, so their findings land in the same receipt, the same PR comment, and the same trust boundary as the built-in gates. AIV does not re-implement these tools; it orchestrates them and reads their SARIF.
## When a finding is wrong
The finding comes from the external tool, not from AIV. Fix or suppress it with that tool's own mechanism (for example `# noqa: AIR311` for ruff or a `.semgrepignore`), then re-run. Rule ids are `external.<tool-id>.<sarif-rule-id>`, so baselines and inline suppressions stay stable across tool versions.
## How to fix
- Run the tool locally with the same command shown in `.aiv/config.yaml`; `{files}` expands to the changed files that match `file_extensions`.
- `severity: warn` makes a tool advisory; anything else blocks. `min_level` drops SARIF results below `note`, `warning`, or `error`.
- If the tool binary is missing or times out, AIV reports it as `SKIPPED` in the summary unless `on_missing: fail`, which turns the absence into a blocking `external.<tool-id>.missing` finding.
- No SARIF output, or output that is not SARIF, always blocks (`.no-output`, `.bad-output`): a tool that ran but said nothing is not evidence.
- The gate is off by default and only runs when `enabled: true` and at least one `tools:` entry exist.
