# Triage replay

AIV Triage is an offline policy replay tool for pull-request operations. It answers a concrete question
before a repository enables automation:

> What would this policy have proposed for our saved PR history, and why?

It does not call GitHub, use a model, read environment variables, comment, label, draft, close, assign,
or rerun CI. Every result says `mode: dry-run` and `side_effects: []`.

For the product model, evidence and non-goals, see [TRIAGE-PRODUCT.md](TRIAGE-PRODUCT.md).

## Quick start

Build the current CLI jar:

```bash
mvn -q -pl aiv-cli -am package
```

Replay the included demonstration snapshot using the starter policy:

```bash
java -jar aiv-cli/target/aiv-cli-2.0.0.jar triage replay \
  --input demos/triage/airflow-like-snapshot.json \
  --policy presets/triage/oss-maintainer.yaml \
  --output-json triage-result.json \
  --output-markdown triage-result.md
```

The demonstration proposes three different outcomes:

- draft a PR with failed required CI
- hold a large newcomer change without an accepted issue
- mark a clean external PR ready for maintainer review

## Command contract

```text
aiv triage replay --input <snapshot.json> --policy <policy.yaml> \
  --output-json <result.json> --output-markdown <result.md> [--as-of <RFC-3339>]
```

All four file options are required. `--as-of` replaces the snapshot capture time for deterministic stale
draft evaluation. The command rejects every normal gate option, including GitHub publication options.

Exit code 0 means replay completed. It does not mean a PR is ready or that an action was performed. Exit
code 2 means the command, input schema or policy schema was invalid.

## Snapshot requirements

The root schema must be `aiv-triage-snapshot/1`. Snapshot data is supplied by a separate capture step,
not fetched during replay. The MVP understands the following data:

| Area | Fields | Why it matters |
|---|---|---|
| PR | number, title, body, draft, labels, timestamps | identify the proposal and replay point |
| Author | association, prior merged PRs, open PRs, failing open PRs, last activity | route newcomers, caps, stale drafts |
| Checks | required failures, missing required checks, conflicts | draft or request a rebase |
| Review | unresolved threads, maintainer engagement | readiness and stale-close protection |
| Branch | commits behind base | request rebase |
| Issue | linked and accepted | require maintainer permission for a large newcomer change |
| CI | CI definition touched | require maintainer CI approval |
| Files | count and changed lines | size threshold |

A missing optional field is never treated as false or zero. A rule needing it is skipped and the Markdown
report names the unavailable fact.

## Starter policy

`presets/triage/oss-maintainer.yaml` implements portable rules found in the maintainer evidence:

| Rule | Proposed action |
|---|---|
| More than three failing open PRs from an external author | close proposal |
| More than five open PRs from an external author | hold proposal |
| Draft inactive for seven days without maintainer engagement | close proposal |
| Required CI failed or missing | draft proposal and remove ready label |
| Conflict or more than 100 commits behind base | request rebase and remove ready label |
| Unresolved review thread | hold review and remove ready label |
| External PR changes CI definitions | require maintainer CI approval |
| Newcomer change of 500+ lines without accepted issue | require maintainer permission |
| All supplied readiness facts clear | mark ready proposal |

Change thresholds and labels for the repository. Replay before a team enables any live workflow.

## Outputs

The JSON result uses `aiv-triage-result/1` and contains:

- snapshot and policy SHA-256 digests
- the evaluation timestamp
- facts used for every PR
- skipped rules and unavailable facts
- ordered proposals, their action IDs and evidence
- `mode: dry-run` and an empty `side_effects` array

The Markdown output is derived from the same result. It is intended for maintainer review, not as a bot
comment.

## What comes next

The MVP deliberately does not capture GitHub data or execute actions. A later live integration must be a
separate opt-in capability with:

1. a read-only capture action that writes versioned snapshots
2. historical replay and action-precision evidence
3. a human approval queue for draft, close, label and status actions
4. explicit GitHub write permissions only after a project enables them

This separation lets a team prove policy behaviour before it grants automation the ability to affect
contributors.
