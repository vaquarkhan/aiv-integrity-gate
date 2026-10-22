# Module: `aiv-report`

`aiv-report` defines audit payload schemas and converts verified AIV audit chains into reviewer-facing evidence: a self-contained HTML report plus optional JSONL files for SQL/analytics tools.

## Architecture role

```text
AIVResult / proposal result -> AuditRecords -> HashChainAudit
                                             |
review labels CSV + audit log -> AuditReport -> HTML + JSONL
```

## `AuditRecords`

Creates deterministic payload maps for:

- gate runs with metadata, gate outcomes, findings, and overrides,
- proposal checks with template/source/content facts,
- audit anchor notices.

The CLI adds time/ref/author/version metadata before append. Verify-mode audit payloads intentionally omit timestamps when byte reproducibility matters.

## `AuditReport`

`load` verifies the chain and optional external head before interpreting records. It aggregates:

- run/pass/block counts,
- gate and rule outcomes,
- override/suppression counts,
- proposal checks,
- reviewer labels and per-rule calibration.

A broken chain is still rendered as visibly unverified evidence and produces a nonzero CLI status.

## CLI

```bash
java -jar aiv-cli.jar report \
  --audit-log .aiv/audit.log \
  --out aiv-report.html \
  --labels reviewer-labels.csv \
  --export-jsonl report-data \
  --expect-head <sha256> \
  --min-labeled 20
```

Outputs:

- static `aiv-report.html` with no server requirement,
- `records.jsonl` and `findings.jsonl` when export is requested.

Example DuckDB queries: [docs/reports/aiv-audit.duckdb.sql](../docs/reports/aiv-audit.duckdb.sql).

## Reviewer labels

Labels join to findings by stable rule/file/line identity. The report promotes a rule recommendation only after the configured minimum labeled findings and zero observed false positives in that labeled set.

This is calibration evidence, not a universal precision estimate. Results are only as representative and correct as the reviewer labels.

## HTML contents

The report includes chain status/head, run summary, gate/rule tables, finding details, override evidence, proposal outcomes, and calibration recommendations. User-controlled values are escaped before rendering.

## Failure behavior

| Condition | CLI result |
|-----------|------------|
| intact chain | report written, exit 0 |
| broken/head mismatch | report written as unverified, exit 1 |
| missing log/bad options/labels | exit 2 |

## Extension points

Add record kinds with versioned fields and backward-compatible readers. Add exports above the verified record model. Keep counts derived from records rather than manually maintained summaries.

## Security

The report is local static HTML. Treat record text as untrusted and escape it. JSONL may contain paths/messages/metadata; classify and retain it like the audit log. External anchoring is handled by `aiv-audit`/CLI.

## Tests and build

`AuditRecordsTest`, `AuditReportTest`, and CLI `EvidenceCommandsTest` cover schemas, chain verification, anchors, labels, calibration thresholds, escaping, HTML, JSONL, broken logs, and command exits.

```bash
mvn -pl aiv-report test
mvn -pl aiv-report verify
```

## Interactions

Depends on `aiv-api` and `aiv-audit`. The CLI is the command surface. DuckDB is optional for querying exported JSONL and is never in the verdict path.
