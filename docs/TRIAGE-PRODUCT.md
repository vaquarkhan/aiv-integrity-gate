# AIV Triage: product contract

Status: MVP implemented as an offline replay command. Author: Viquar Khan.

## Product statement

AIV Triage turns a maintainer's pull-request operating policy into deterministic, replayable action
proposals. Before a project enables a live automation rule, it replays that rule against saved PR history
and reports the action count, evidence, and later outcomes.

AIV Triage is not an AI-authorship detector, an AI-slop classifier, or an autonomous maintainer. It
covers the mechanical part of PR operations: readiness, contributor limits, stale drafts, accepted-issue
requirements, CI safety, and queue routing.

## Unique value

A generic linter checks code. An AI reviewer gives an opinion. AIV Triage answers a different question:

> If this project policy had run on our previous pull requests, what exactly would it have proposed, and
> how often would that have disagreed with the outcomes maintainers later chose?

This makes policy automation testable before it touches a live PR.

## MVP safety contract

`aiv triage replay` is offline and side-effect free.

- It reads only a caller-supplied saved snapshot and a caller-supplied policy YAML file.
- It does not invoke GitHub APIs, create a `HttpClient`, publish a check, comment, label, draft, close,
  assign, rerun CI, edit a local repository, read environment variables, or consult the current clock.
- It returns `mode: dry-run` and `side_effects: []` in every JSON result.
- Every proposal has stable action IDs, source rule IDs and the literal facts that caused it.
- Missing input is not guessed. A rule requiring a missing field is listed as skipped with its unavailable
  fact names.
- The replay `as_of` timestamp comes from the snapshot or explicit command option, so results are
  reproducible.

A later live product may execute only a subset of proposed actions. That requires a separate design,
explicit GitHub permissions, human approval and replay precision evidence.

## CLI

```text
aiv triage replay --input <snapshot.json> --policy <policy.yaml> \
  --output-json <result.json> --output-markdown <result.md> [--as-of <RFC-3339>]
```

It accepts no gate-run or publication option. Any unknown option, missing required argument, duplicate
argument, unsupported subcommand or invalid input returns exit code 2. A successful replay returns 0 even
when it proposes a draft or close action, because it never changes a PR.

## Input schema: `aiv-triage-snapshot/1`

```json
{
  "schema_version": "aiv-triage-snapshot/1",
  "repository": "example/project",
  "captured_at": "2026-10-24T00:00:00Z",
  "pull_requests": [
    {
      "number": 123,
      "title": "Fix scheduler retry",
      "body": "Fixes #120. Tests: ...",
      "created_at": "2026-10-23T12:00:00Z",
      "updated_at": "2026-10-23T13:00:00Z",
      "draft": false,
      "labels": ["area:scheduler"],
      "author": {
        "login": "contributor",
        "association": "CONTRIBUTOR",
        "prior_merged_prs": 0,
        "open_prs": 1,
        "failing_open_prs": 0
      },
      "checks": {"required_failed": 0, "required_missing": 0, "conflicts": false},
      "review": {"unresolved_threads": 0, "maintainer_engaged": false},
      "branch": {"commits_behind_base": 0},
      "issue": {"linked": true, "accepted": true},
      "ci": {"definition_touched": false},
      "files": {"changed": 3, "lines_changed": 80}
    }
  ]
}
```

Only these fields are supported in the MVP. `files` values are pre-captured metadata, not a fresh Git
calculation. Snapshot generation is deliberately out of scope for the offline command.

## Policy schema: `aiv-triage-policy/1`

A policy is a small, portable set of thresholds and action labels. It does not embed arbitrary code.

```yaml
schema_version: aiv-triage-policy/1
name: oss-maintainer
labels:
  ready: ready-for-maintainer-review
  needs_permission: maintainer-permission-needed
  ci_safety: maintainer-ci-approval-needed
thresholds:
  external_associations: [CONTRIBUTOR, FIRST_TIME_CONTRIBUTOR, FIRST_TIMER, NONE]
  newcomer_prior_merged_prs: 0
  newcomer_large_change_lines: 500
  max_open_prs: 5
  max_failing_open_prs: 3
  stale_draft_days: 7
  behind_base_commits: 100
rules:
  - id: draft-required-ci-failure
    enabled: true
  - id: rebase-conflicted-or-stale-branch
    enabled: true
  - id: hold-unresolved-review
    enabled: true
  - id: hold-large-newcomer-without-accepted-issue
    enabled: true
  - id: hold-external-ci-definition-change
    enabled: true
  - id: close-author-quality-violations
    enabled: true
  - id: hold-open-pr-limit
    enabled: true
  - id: close-stale-draft
    enabled: true
  - id: ready-for-maintainer-review
    enabled: true
```

The rule IDs are fixed in the MVP, which keeps a proposed action auditable. A later policy compiler can
introduce generic conditions only after action precedence and missing-data behaviour are proven.

## Rule catalogue and proposal precedence

The evaluator processes each PR in this order. A terminal proposal suppresses lower-priority proposals.

| Priority | Rule | Required facts | Proposal |
|---:|---|---|---|
| 1 | `close-author-quality-violations` | external author, failing open PR count above limit | `close-pr` |
| 2 | `hold-open-pr-limit` | external author, open PR count above limit | `hold-open-pr-limit` |
| 3 | `close-stale-draft` | draft, author has not acted for configured days, no maintainer engagement | `close-pr` |
| 4 | `draft-required-ci-failure` | external author, one or more required check failures or missing checks | `draft-pr`, remove ready label, update status |
| 5 | `rebase-conflicted-or-stale-branch` | conflict, or commits behind base above limit | `request-rebase`, remove ready label |
| 6 | `hold-unresolved-review` | unresolved review thread | `hold-review`, remove ready label |
| 7 | `hold-external-ci-definition-change` | external author changed CI definition | `require-maintainer-ci-approval` |
| 8 | `hold-large-newcomer-without-accepted-issue` | newcomer, large change, no accepted issue | `require-maintainer-permission` |
| 9 | `ready-for-maintainer-review` | external author and every readiness fact is supplied and clear | `mark-ready` |

An action is only a proposal. The Markdown output tells a maintainer what would happen but never sends it
anywhere.

## Evidence metrics

The result reports facts and proposals for each PR. A companion replay analysis can calculate:

- proposal count by rule and action
- action rate by contributor tier and area
- later outcome after an historical proposal, where a snapshot includes outcome fields
- proposed-action reversal rate, such as a PR that would have been drafted but later merged unchanged
- CI minutes avoided, where the snapshot includes captured CI-cost metadata

A project should enable a live action only after it sets its own precision threshold. The suggested
starting threshold is 95% precision for draft/close automation and 99% for any irreversible action.

## Non-goals

- deciding whether a PR should merge
- classifying code or an author as AI-generated
- predicting subjective design quality
- replacing maintainer conversations
- live GitHub reads or writes in this command
- model calls, token budgets or JEV questions

## Relationship to existing AIV components

- Integrity gates remain the pre-PR and CI evidence layer.
- `change-policy` remains the per-PR review-cost policy layer.
- AIV Triage uses queue and history facts to decide which operational proposal a maintainer should see.
- JEV and external AI review remain optional advisory inputs after deterministic routing.
- Magpie can consume replay output or own human judgment workflows. AIV does not replace it.
