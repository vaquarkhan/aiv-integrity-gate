# Apache Magpie and AIV: how they fit together

Author: Viquar Khan

Short version: **AIV decides facts, Magpie helps people act on them.** AIV is a deterministic gate
that runs in CI and gives the same answer every time for the same commit. Apache Magpie is a set of
agent skills that help maintainers triage, review, mentor and handle security reports, with a human
confirming every action. They do not compete. Each fills the other's stated gap, and both projects'
own principles say the deterministic check should run first.

Everything about Magpie below was read from the local clone at `../magpie-review` (apache/magpie,
commit `ccb53a3`). File paths in Magpie are given relative to that clone. Magpie is an Apache Software
Foundation project; AIV is not, and nothing here implies endorsement by either.

## 1. What each one is

| | AIV Integrity Gate | Apache Magpie |
|---|---|---|
| Form | Java CLI and GitHub Action, one required status check | Agent skills (80 `SKILL.md` files in 10 families) plus Python helper tools, run by Claude, Codex, Gemini, Copilot, Kiro and others |
| Runs | In CI on every PR head, without a model | On a maintainer's machine or agent session, with a model |
| Output | Pass or block, a JSON/SARIF receipt, optional labels and comments | Proposals: comments, labels, drafts, review drafts, closures, each confirmed by a maintainer |
| Decides | Objective facts about the added lines: merge conflict blocks, parse errors, edit tool artifacts, placeholders, unapproved gitlinks, hidden Unicode, policy file changes | What a maintainer should do next with each PR or issue, and the wording of what gets said |
| Never does | Call a model, judge intent or authorship | Merge, approve without a person, block a PR by itself |

## 2. Why the order is AIV first, Magpie second

Both projects already say so:

- Magpie `PRINCIPLES.md` section 6: outputs are probabilistic and gates are deterministic. Where a
  script or linter can replace a model pass, it runs first, and model passes are not spent on what
  code already decides.
- Magpie `PRINCIPLES.md` section 7: every agent output is a proposal a human signs off on; the agent
  never merges its own work.
- AIV keeps every subjective signal advisory and blocks only on objective defects, so its verdict can
  sit in branch protection.

Magpie has no merge-blocking check of its own (its quick-merge skill "surfaces candidates; it does not
merge"). AIV has no judgement, mentoring or communication layer. Put together, the deterministic part
gates and the probabilistic part advises, which is the boundary both sets of principles draw.

## 3. Where each one is strong and where it is not

| Concern | Better owner | Why |
|---|---|---|
| Unresolved conflict blocks, parse failures, elision and SEARCH/REPLACE artifacts, placeholders | AIV | Provable from the bytes, reproducible, cheap, no model needed |
| Unapproved submodule pointers, policy file drift, AI instruction file edits, hidden Unicode | AIV | Structural facts from Git, checked against the protected base branch |
| A reproducible, auditable record of why a PR was blocked | AIV | JSON/SARIF receipt and an optional tamper-evident audit log (`aiv audit verify`); Magpie keeps session summaries, not attestations |
| Which PRs need attention today, and what to do with each | Magpie | Triage reads CI state, threads, staleness, contributor history and project config |
| Is this PR in scope, does it hallucinate an API, is the test meaningful | Magpie (review drafts) and human review | Needs reading the diff with judgement |
| How to tell a newcomer what to fix, in the project's voice | Magpie | Mentor and comment templates, AI attribution footer, maintainer sign-off |
| Security reports, CVE handling, release votes | Magpie | Out of AIV's scope entirely |
| Deciding to merge | Maintainers, with branch protection | Neither tool merges |

Overlap exists in slop detection. Magpie's code-review skill has a slop early exit (two hard signals,
or one hard plus three soft, in `plugins/magpie-pr-management/skills/code-review/classifications/slop-early-exit.md`),
and some of its signals need model judgement. AIV's objective rules are a strict, model-free subset.
The best split: AIV blocks what can be proved; Magpie's slop signals stay as review context.

## 4. The best combination

```text
Contributor
  (optional) AI-DLC or their own assistant, with the AIV sensor before opening the PR
        |
        v
Pull request
  -> AIV Integrity Gate (required check)
        receipt: JSON / SARIF, optional aiv:review-<tier> label, optional fix brief
        |
        +-- BLOCK --> Magpie triage sees a failed static check
        |               -> proposes a code-fix comment linking the AIV guidance (no rerun)
        |               -> contributor fixes with the fix brief, pushes, AIV runs again
        |
        +-- PASS ---> Magpie triage counts AIV as real CI
                        -> passing PRs reach "mark ready" and quick-merge screening
                        -> Magpie code-review drafts a review (never approves on red CI)
                        -> optional Copilot review, only if AIV passed and the tier allows it
                        -> maintainer decides
  -> AIV review ledger records what was fixed, merged, and said
        -> periodic report proposes promoting or retiring AIV rules for a maintainer to decide
```

### 4.1 AIV as Magpie's real CI and static check (config only)

Magpie triage already reads every check run (`tools/pr-management/src/pr_management/ci.py`). Three
config rows from `integrations/magpie/magpie-config/` make it treat AIV correctly:

| Magpie key | AIV value | Effect in Magpie |
|---|---|---|
| `real_ci_patterns` | `^AIV Integrity Gate$` | a PR is never `passing` on bot checks alone; a green AIV counts |
| `static_check_patterns` | `AIV Integrity Gate` | an AIV failure routes to `static-check-failure` (row 12 or 12b): a code-fix comment, not a rerun |
| check map row | `AIV Integrity Gate` with the AIV guidance URL | Magpie's violation comment links the author to the AIV docs |

Because Magpie's quick-merge gates require real CI green and no failed checks (gates G1 to G7 in
`tools/pr-management/README.md`), and its code-review skill never approves a PR that fails CI
(golden rule 8 in `plugins/magpie-pr-management/skills/code-review/SKILL.md`), one config change makes
AIV's verdict flow through triage, quick-merge and review drafts.

This is verified, not assumed: `integrations/magpie/test_magpie_with_aiv.py` loads the rows with
Magpie's own config loader and runs Magpie's own triage classifier on PR fixtures built with Magpie's
own test helpers (6 tests pass against commit `ccb53a3`).

### 4.2 Use the full job name

Magpie matches `static_check_patterns` as a case-insensitive substring in either direction. A short
pattern such as `aiv` also matches unrelated checks like `naive-tests` (the proof test shows this with
Magpie's matcher). Name the AIV job `AIV Integrity Gate` and use that full name in both rows.

### 4.3 Let AIV decide how much model attention a PR gets

AIV's opt-in `review-routing` gate labels each PR `aiv:review-<tier>` from facts (paths, size). Magpie
selectors read labels, so a maintainer can run triage or review on, for example,
`label:aiv:review-balanced` first, and keep `aiv:review-none` PRs (CI definitions, security policy)
for purely human review. Magpie itself keeps cloud models away from private security content; the tier
label extends the same idea to public PRs the project marks as sensitive.

### 4.4 Give the contributor the facts, give the maintainer the words

When AIV blocks, `--output-fix-brief` produces a brief with the exact rule, file, line and a fixed
hint, with PR text sanitised and labelled as data. Magpie's comment, confirmed by a maintainer, links
to guidance and keeps the project's tone. The contributor gets precision from AIV and context from
Magpie, and nobody has to retype a merge-conflict explanation.

### 4.5 Protect the instructions both tools rely on

Magpie skills, `AGENTS.md`, `CLAUDE.md` and `.github/copilot-instructions.md` all steer AI tools, and
review tools read them from the PR branch. With `review_instruction_paths` and `hidden_unicode`
configured, AIV flags any PR that edits those files or hides invisible characters in them, so a PR
cannot quietly retune the agent that reviews it. Magpie's own `.apache-magpie-overrides/` directory is
a good candidate to add to `review_instruction_paths` in projects that adopt Magpie.

### 4.6 Close the loop with evidence

The AIV review ledger (`integrations/review-ledger/`) records, per PR, which AIV findings were fixed
before merge, which were merged anyway, and which AI review comments were acted on. Its report
proposes, with 95% confidence intervals and a minimum sample, which advisory AIV rules to promote and
which to retire, and lists AI findings on files AIV did not flag. That list is where the next
deterministic rule comes from: a maintainer writes it and its tests, and from then on AIV handles that
case before Magpie or any model sees it. This matches Magpie's own rule that a mode only graduates on
evidence (`PRINCIPLES.md` section 8).

## 5. Setup checklist

1. Run AIV as a required status check with trusted base-branch policy
   (`--require-trusted-config`), job name `AIV Integrity Gate`.
2. Add the three Magpie rows from `integrations/magpie/magpie-config/` to the project's Magpie config.
3. Optional: enable `review-routing` and `--label-review-tier`; add `review_instruction_paths`
   (including `.apache-magpie-overrides/**` and agent instruction files) and `hidden_unicode`.
4. Optional: upload `--output-fix-brief` as a CI artifact so contributors can use it.
5. Optional: record the ledger and review its report every release cycle.
6. Keep Copilot or any other AI approval from counting toward merge; AIV stays the required check and
   maintainers stay the approvers.

## 6. Anti-patterns

- **Letting a model's slop verdict block a PR.** Keep model signals as review context. Promote a
  signal to an AIV rule only when it can be decided from the bytes and has tests.
- **Rerunning a failed AIV check.** It is deterministic and will fail again. That is why the Magpie
  config routes it to a code-fix comment.
- **Auto-posting fixes or comments.** Neither tool should speak for a maintainer without sign-off
  (Magpie `PRINCIPLES.md` section 7), and AIV never posts the fix brief.
- **Treating AI disclosure as a defect.** Both tools keep disclosure as context. AIV's
  `invariant.ai-provenance` only flags trailers pasted into source files, not commit trailers.
- **Short check-name patterns.** See 4.2.
- **Pooling numbers.** AIV's benchmark keeps fixture, structural and real-PR suites separate; do not
  quote one combined "AI slop caught" percentage for the pair.

## 7. What is measured and what is not

| Claim | Status |
|---|---|
| Magpie's classifier routes an AIV failure to a code-fix comment and counts a green AIV as real CI | verified by `integrations/magpie/test_magpie_with_aiv.py` against Magpie `ccb53a3` |
| AIV blocks conflict blocks, unapproved gitlinks and the configured artifact classes on the Airflow fixtures and structural suites | verified by the AIV benchmark (35/35 fixtures, 2/2 structural, Airflow #59957) |
| The combination reduces maintainer time per PR | not measured; record it with the ledger and Magpie's session summaries before claiming it |
| Magpie end to end on a live repository with AIV configured | not run |

## 8. Where to go next

- Run both on a sandbox fork of a project that already uses Magpie, record a release cycle of ledger
  data, and publish the first promotion and retirement report.
- Offer the three Magpie config rows upstream as a documented example once a project has used them.
- If Magpie adds SARIF ingestion, AIV's SARIF report already carries rule ids, files and lines.

See also: `integrations/magpie/README.md`, `docs/pipeline-aiv-copilot.md`,
`docs/AI-integration/AI-REVIEW-INTEGRATION-HANDOFF.md`.
