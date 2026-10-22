# Module: `aiv-proposal`

`aiv-proposal` applies deterministic, template-driven integrity checks to improvement proposals stored in files, Confluence-style pages, issue trackers, or custom sources. KIP, FLIP, and SPIP are shipped templates/examples; teams can add their own.

## Architecture role

This is a CLI subdomain, not a `QualityGate`. `ProposalCli` resolves a source/template, `ProposalChecker` evaluates structural facts, and `AuditRecords` can append the result to the same evidence chain as code verdicts.

## Commands

```bash
# one document
java -jar aiv-cli.jar proposal check --template kip --source proposal.md

# remote/indexed source
java -jar aiv-cli.jar proposal check --template custom.yaml --source <url-or-issue>

# recent documents
java -jar aiv-cli.jar proposal scan --template flip --last 30 --out results/
```

Options support JSON output, history comparison, audit log, and `--fail-on-findings`.

## Templates

A template defines required sections, state/acceptance policy, issue/discussion/external-document expectations, placeholder/template text, indexing/number rules, and other deterministic checks.

Shipped templates:

- `kip.yaml`,
- `flip.yaml`,
- `spip.yaml`.

Use these as examples for any organization proposal/ADR/RFC format.

## Runtime flow

1. Load named or custom template.
2. Resolve source into normalized document id/title/url/content/metadata/history.
3. Normalize wiki/Markdown headings and markup.
4. Extract state/number/sections/references.
5. Check required structure and objective artifacts.
6. Compare indexes/history when supplied.
7. Return findings plus content SHA-256 and source facts.
8. Optionally write JSON/summary and append audit record.

## Finding classes

Checks include missing sections, leftover template prompts, placeholders, assistant chat artifacts, number/index collisions, missing issue/discussion links, missing external document evidence, state/acceptance mismatch, and content changed after acceptance.

## Sources

Local files require no network. Remote Confluence/JIRA-style sources and scans use HTTP and preserve URL/content hash in evidence. Credentialed private document stores are not fetched unless a source adapter explicitly supplies authentication. Mailing-list vote counting is not implemented.

## Exit behavior

By default findings are advisory and command exit remains 0; `--fail-on-findings` makes findings exit 1. Usage/config errors return 2. Source retrieval errors return 3. A broken audit history is refused.

## Example custom template

```yaml
id: architecture-rfc
required_sections:
  - Summary
  - Motivation
  - Design
  - Alternatives
  - Testing
placeholder_patterns:
  - "TBD"
  - "Describe the design here"
```

## Trust and reproducibility

Checks are deterministic over captured content/template/index/history. Remote pages are mutable, so the content hash identifies exactly what was checked. Store the audit head externally when the record must be independently verifiable.

## Extension points

- Add a YAML template, not Java, for a new proposal format.
- Add a source adapter when a new store needs normalization/authentication.
- Add checks only for objective template/state/history facts.
- Keep organization-specific voting/approval semantics in templates/adapters.

## Tests and build

`ProposalCheckerTest`, `ProposalCliTest`, and `MarkupAndSourcesTest` cover templates, headings/markup, sources via local HTTP server, states, sections, collisions, history, outputs, auditing, and exits.

```bash
mvn -pl aiv-proposal test
mvn -pl aiv-proposal verify
```

## Interactions and boundary

Depends on API/audit/report structures and is bundled by the CLI. It checks proposal integrity/completeness, not technical merit, consensus, or whether a proposal should be accepted.
