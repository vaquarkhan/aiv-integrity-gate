# Evidence and proposals: audit chain, reports, and KIP/FLIP/SPIP checks

**Author:** Viquar Khan

This guide covers three things that sit around the gates: a tamper-evident record of every run, a report that turns that record into a calibration decision, and advisory checks for improvement proposals that live outside git.

---

## 1. Tamper-evident audit log

### Turn it on

```yaml
# .aiv/config.yaml on the protected branch
audit_log: .aiv/audit/aiv.log
```

or per run: `--audit-log <file>`, or `AIV_AUDIT_LOG`. Precedence: flag, then environment, then config. The config key is read from the base ref like every other key, so a pull request cannot redirect or disable its own record.

Each run appends one `aiv-gate-run/1` record and prints:

```text
[AUDIT] record #12 appended to .aiv/audit/aiv.log; chain head 1bd813b4...
```

The same line is added to the JSON report notices and, when the deterministic findings comment is enabled, to the PR comment.

### What a record contains

Timestamp, CLI version, base and head refs and their commit SHAs, commit author, where the config came from (`base-ref` or `workspace`), exit code, every gate with its findings (rule, file, line), the notices, and an `overrides` object:

| Field | Set when |
|---|---|
| `config_change` | The change edits `.aiv/` (notice `[CONFIG-CHANGE]`). |
| `skip_ignored` | `/aiv skip` was present and refused (notice `[SKIP-IGNORED]`). |
| `skip_honored` | `/aiv skip` was honoured for an allowlisted, signed author (notice `[SKIP-HONORED]`, with the email). |
| `suppressed` | A baseline or inline `aiv-disable` removed findings (notice `[SUPPRESSED]`, with the rule ids). |

These answer the question a maintainer asks after an incident: who switched what off, and when.

### Verify it

```bash
java -jar aiv-cli.jar audit verify .aiv/audit/aiv.log
java -jar aiv-cli.jar audit verify .aiv/audit/aiv.log --expect-head 1bd813b4...
```

Exit `0` intact, `1` tampered or anchor mismatch. A retroactive edit, deletion or reordering is reported with the index of the first broken record.

### Threat model, plainly

The chain is `h_i = SHA256(h_(i-1) || record_i)`. Without an anchor it proves internal consistency only: someone with write access to the file can rewrite it and recompute every hash. It becomes tamper-evident when the head is kept somewhere the writer does not control: the CI job log, the PR comment (GitHub keeps comment edit history), a signed tag, or a transparency log. `--expect-head` checks against that anchor. The log is not encrypted and holds file paths and author emails; keep it where your CI keeps artifacts. Rekor or another transparency log is not integrated.

---

## 2. Evidence report

```bash
java -jar aiv-cli.jar report --audit-log .aiv/audit/aiv.log --out aiv-report.html \
    --labels labels.csv --min-labeled 20 --export-jsonl out/
```

The chain is verified before anything is counted; a broken chain still produces a report with the break called out, and the command exits `1`.

The report is static HTML with no script and no external requests: per-rule findings, runs, files, findings seen in blocking versus advisory gate results, reviewer labels and a calibration verdict, per-gate failures, override counts, and proposal checks.

### Calibrating a rule before you make it blocking

Reviewers label findings in a CSV: `rule_id,file,line,verdict` with `tp` or `fp`. The verdict per rule:

| Verdict | Meaning |
|---|---|
| `needs labels (n/m)` | Fewer than `--min-labeled` labelled findings. |
| `ready to promote` | Enough labels and zero false positives: safe to move from `severity: warn` to `fail`. |
| `keep advisory` | At least one false positive on an advisory rule. |
| `review: false positives on a blocking rule` | A blocking rule produced a false positive; fix or demote it. |
| `blocking, calibrated` | Blocking only, enough labels, zero false positives. |

This is the "zero false positives on 90 days of merged pull requests" procedure from the Airflow tutorial, made mechanical.

### SQL over the log (DuckDB, optional)

`--export-jsonl out/` writes `records.jsonl` and `findings.jsonl`. `docs/reports/aiv-audit.duckdb.sql` has queries for rules by volume, findings per day per gate, overrides over time, and the latest state of each proposal. DuckDB never sits in the verdict path: the chain is the record, the JSONL is a disposable view. Apache Derby was considered and not used: it is a row store for transactions, not for this kind of aggregate.

### Demo on the public bench

`benchmarks/airflow-bench-compare/run_audit_demo.py` runs every branch of `aiv-airflow-bench` with `--audit-log`, verifies the 89-record chain, writes the report and runs the DuckDB queries (`results-audit/`). The labels there are derived from the bench's own branch names (a blocking finding on `labeled/proper-*` is a false positive; an `invariant` or `design` finding on `labeled/slop-*` is a true positive). That ground truth is circular, so the demo shows the mechanics of calibration, not the precision of any rule on real pull requests.

---

## 3. Improvement proposals (KIP, FLIP, SPIP)

### Where Apache projects keep proposals

| Project | Proposal | Lives in | Decided on |
|---|---|---|---|
| Kafka | KIP | Confluence (cwiki.apache.org), state in a "Current state" field | dev@ DISCUSS and VOTE threads |
| Flink | FLIP | Confluence, state as a page label; some pages embed Markdown | dev@ |
| Spark | SPIP | JIRA ticket labelled SPIP, often with a linked Google Doc | dev@ vote: 72 hours, at least 3 binding +1, no binding -1 |
| Airflow | AIP | Confluence | dev@ |

The record that counts at the ASF is the mailing list. Wiki pages and documents are where the text lives.

### Run it

```bash
# one page
java -jar aiv-cli.jar proposal check --template kip \
    --confluence-url "https://cwiki.apache.org/confluence/display/KAFKA/KIP-1102%3A+..." --index

# the 50 newest proposals of a space, with an audit chain and a Markdown summary
java -jar aiv-cli.jar proposal scan --template kip --space KAFKA --last 50 --index \
    --audit-log audit/proposals.log --out kip-scan/

# an SPIP in JIRA, or a file on disk
java -jar aiv-cli.jar proposal check --template spip --jira SPARK-12345
java -jar aiv-cli.jar proposal check --template-file my-template.yaml --file proposal.md
```

Findings are advisory: the exit code is `0` unless `--fail-on-findings`. Network sources use public, read-only endpoints with no credentials. Nothing is written back to the wiki, JIRA or the list.

### Rules

| Rule | Meaning |
|---|---|
| `proposal.missing-section` | A section the template requires is not there (numbered headings, bold or large-font paragraph headings and Markdown macros are understood). |
| `proposal.empty-section` | Fewer words of its own than the template minimum, after removing template instructions. |
| `proposal.template-text` | One of the template's instruction sentences is still in place. |
| `proposal.placeholder` | `TBD`, `TODO`, `FIXME`, `XXX` (uppercase), lorem ipsum. |
| `proposal.assistant-artifact` | Text left by a chat assistant ("Thinking...", "As an AI language model", ...). |
| `proposal.missing-state`, `proposal.unknown-state` | No state, or a state the project does not use (including the template's own "[One of ...]" text). |
| `proposal.accepted-without-thread` | Accepted, but no mailing-list thread is linked. |
| `proposal.accepted-without-issue` | Accepted without a tracking issue, when the template requires one. |
| `proposal.number-collision` | Another page in the space uses the same number (`--index`). |
| `proposal.unknown-reference` | Cites a proposal number no page uses (`--index`). |
| `proposal.changed-after-acceptance` | The content (status section excluded) differs from the first time the audit chain saw it accepted. The CLI refuses to compare against a broken chain. |
| `proposal.external-doc` | Content is in a document AIV cannot read and that is not archived on ASF infrastructure. |

### Templates

Shipped: `kip`, `flip`, `spip` (in `aiv-proposal/src/main/resources/proposal-templates/`). Each records its source of truth and the calibration it went through. A project writes its own with the same keys and passes `--template-file`.

### Sources AIV does not read

- **Google Docs, Quip, Notion, SharePoint, Dropbox:** not fetched, by design. Reading them would need the reader's credentials, and the content is not part of the ASF record. A link to one is reported as `external-doc`, and missing sections say the content may be there.
- **Mailing-list votes:** not counted. On a sample vote thread the lists.apache.org search API returned two of the messages, so a binding-vote count built on it would be wrong. The `accepted-without-thread` rule checks that a thread is linked; counting votes stays with the shepherd.

### Pilot

Last 50 KIPs and last 30 FLIPs, October 2026, after one calibration pass on the same pages: 9 of 50 KIPs and 7 of 30 FLIPs have findings, and every finding was checked against the page. One KIP's sections each contain only "Thinking...". Details, including every false positive found and fixed, in [benchmarks/proposal-pilot/README.md](../benchmarks/proposal-pilot/README.md).
