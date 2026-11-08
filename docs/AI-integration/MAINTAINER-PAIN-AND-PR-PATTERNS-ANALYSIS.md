# Maintainer pain and PR change patterns: evidence for the change policy engine

Author: Viquar Khan. Status: analysis, input to [CHANGE-POLICY-ENGINE-DESIGN.md](CHANGE-POLICY-ENGINE-DESIGN.md) v2.

This document records what maintainers actually complain about and what the stored PR data says about
each proposed signal. Several signals I expected to work do not separate merged from closed PRs, and
the design was changed because of that.

Data comes from the sibling benchmark repository `aiv-airflow-bench`. Both analyses can be re-run:

```text
python benchmarks/airflow/scripts/mine-change-patterns.py          [--bench ../aiv-airflow-bench] [--out <dir>]
python benchmarks/airflow/scripts/mine-maintainer-pain-themes.py   [--bench ../aiv-airflow-bench] [--out <dir>]
```

## 1. Summary

1. The main maintainer pain is review capacity, not bad code as such. Making a PR is cheap, reviewing
   it is not. Airflow's contributor queue grew from about 260 PRs (January 2026) to about 870
   (September 2026) and fell to about 590 only after a cap of 5 open PRs per non-collaborator.
2. Maintainers say most triage is deterministic: "more than 90%" of problematic PRs (March) and "about
   80%" of triage actions (June) need no model. That is the space AIV can own.
3. Size matters only at the extreme. Merge rate is flat from 1 to 99 files and drops to 46.2% at 100+
   files, yet 92 of those 199 PRs were merged. Size is a review-cost budget, not an AI signal.
4. The strongest patch-level signal is **source change without tests**: 200+ source lines and no test
   lines merge at 43.0% against a 61.7% baseline (closed/merged lift 2.13). This matches the GCP
   "untested provider changes" thread.
5. Three signals I proposed in design v1 are **inverted** (more common in merged PRs) and must not
   block: mixed file categories, intra-PR duplication, and duplicated function names.
6. Reuse of existing helpers is a real research finding but **no Airflow maintainer complained about
   it** in 29 threads. Duplicated *tests* and duplicate *PRs* were raised; reinvented utilities were not.
7. Some pain belongs to Magpie or GitHub, not AIV: per-author volume, duplicate PRs across the queue,
   engagement history, and bot-comment noise.

## 2. What maintainers say (Airflow dev@, 29 threads, November 2025 to October 2026)

Counted only when a person raises the problem themselves. Meeting-note items are note-taker summaries.

| # | Pain | People | Example evidence |
|---|---|---:|---|
| P1 | Review capacity is the bottleneck | 8 | "These days the bottleneck is clearly reviewing PRs and not creating PRs" (Vincent Beck) |
| P2 | Huge, growing queue; ready PRs wait with no comment | 5 | 1,024 open PRs; "anything beyond 200 isn't manageable" (Christos Bisias) |
| P3 | Drive-by AI slop nobody read | 8 | "I'm bored of wasting my time on drive-by AI slop" (Ash Berlin-Taylor) |
| P4 | Bulk PRs from single accounts | 3 | batches of 25 to 50 PRs; top 3 authors held 96 open PRs |
| P5 | Authors vanish or cannot explain the change | 5 | about 60% of triaged PRs closed with no author response |
| P6 | The "why" is missing | 5 | "Agents are very good at producing PRs with no real motivation behind them" (Kaxil Naik) |
| P7 | Description does not match the diff | 1 | description claimed 22 test cases, diff added 7 test lines; PR was 648 commits behind main |
| P8 | Untested provider and live-service changes | 5 | Google provider PRs with only mocked tests; regression in merged #74305 |
| P9 | Duplicated or out-of-place tests | 2 | "same test case already exists somewhere in the test suite" (Przemyslaw Mirowski) |
| P10 | Unrelated file changes bundled in | 2 | "changing unrelated files" named as a predictable agent pattern (Sameer Mesiah) |
| P11 | Several PRs for one issue | 2 | issue #68382 with 7+ PRs; Jarek Potiuk disagrees it is a problem |
| P12 | Large PRs are expensive | 3 | proposal: under 10 lines excluding tests for non-collaborator core PRs |
| P13 | Bot and AI review comment noise | 5 | triage and Copilot comments bury human comments |
| P14 | Agent instructions and templates not followed | 4 | "AI instructions cannot be trusted" (Ash Berlin-Taylor) |
| P15 | Triage false positives | 4 | a critical production fix was drafted by the triage agent |
| P16 | Gen-AI disclosure field unreliable | 3 | field often missing; fabricated case had the box unchecked |
| P17 | Shared CI cost per push | 3 | one workflow hit 40 concurrent jobs against an ASF limit of 20 |
| P18 | Agent commit churn after review | 2 | dozens of review comments answered by agent pushes, then closed |
| P19 | Bloated AI-written docs | 4 | a 100-line troubleshooting doc that could have been 5 lines |
| P20 | Commit hygiene, assignment gaming, prompt injection | 1 each | conventional-commit prefixes, commits without body, unlinked authors |

Adopted mitigations so far: the 5-open-PR cap, the auto-triage "Drafting" process, de-noised triage
comments, and the Gen-AI disclosure checkbox. Proposed: "your PR, your CI" (PRs touching `.github/`,
`dev/breeze/` or `scripts/ci/` stay draft until a maintainer runs project CI), required template
headings checked in CI, commit-message rules, real test evidence for Google provider PRs, and per-area
size caps.

Two lessons for AIV. First, P15: wrong drafting of a legitimate PR costs trust, so every new rule starts
advisory and is measured on merged PRs. Second, P13: AIV output must stay short and must not add a
second noisy bot voice.

## 3. What the PR data says

### 3.1 Size (full index, 15,575 Airflow PRs)

| Files changed | 1 | 2 to 5 | 6 to 19 | 20 to 49 | 50 to 99 | 100+ |
|---|---:|---:|---:|---:|---:|---:|
| merge rate | 82.7% | 73.8% | 80.5% | 76.9% | 71.9% | 46.2% |

| Group | 50+ files | 100+ files | 1,000+ lines |
|---|---:|---:|---:|
| merged | 1.7% | 0.8% | 6.1% |
| closed unmerged | 4.2% | 3.1% | 15.7% |
| AI disclosed | 2.2% | 1.2% | 9.4% |
| not disclosed | 2.3% | 1.4% | 8.3% |

Titles that promise a small change (fix, typo, bump, minor, docs) and touch 20+ files: 117 PRs, 49.6%
merged against 70.0% for the same titles under 20 files.

### 3.2 Patch-level signals (1,991 PRs with stored patches)

Airflow 1,148 plus curl 241, godot 177, flink 127, ghostty 124, kafka 100, cosmic 74. Decided: 1,105
merged and 686 closed (baseline merge rate 61.7%); 200 open; 699 AI-flagged. Lift is closed rate over
merged rate; above 1 means the signal is more common in rejected PRs.

| Signal | Merged | Closed | Lift | Merge rate when flagged | AI-flagged | Not AI | Verdict |
|---|---:|---:|---:|---:|---:|---:|---|
| 200+ source lines, no test lines | 3.3% | 7.1% | 2.13 | 43.0% | 1.9% | 8.2% | **keep, strongest** |
| lockfile changed | 4.5% | 7.0% | 1.55 | 51.0% | 4.9% | 5.1% | keep, advisory |
| CI files plus source | 1.3% | 1.9% | 1.50 | 51.9% | 1.3% | 1.6% | keep, advisory |
| whitespace-only file | 1.4% | 2.0% | 1.50 | 51.7% | 0.9% | 1.8% | keep, advisory |
| small-intent title, 10+ reviewable files | 1.2% | 1.6% | 1.36 | 54.2% | 1.6% | 1.3% | keep |
| 3+ commented-out code lines | 3.2% | 3.8% | 1.20 | 57.4% | 3.0% | 3.8% | weak |
| comment-only source change | 4.3% | 5.0% | 1.17 | 58.0% | 3.7% | 4.9% | weak |
| dependency manifest changed | 10.2% | 9.8% | 0.96 | 62.8% | 7.7% | 10.6% | no signal |
| 1,000+ reviewable lines | 7.3% | 5.2% | 0.72 | 69.2% | 9.6% | 5.0% | inverted in this sample |
| 3+ reviewable file categories | 24.5% | 15.2% | 0.62 | 72.3% | 32.2% | 14.9% | **drop** |
| duplicated function names in PR | 14.7% | 9.0% | 0.62 | 72.3% | 20.7% | 8.7% | **advisory only** |
| intra-PR clone (exact) | 7.1% | 3.8% | 0.53 | 75.2% | 8.6% | 4.5% | **advisory only** |
| intra-PR clone (renamed) 2+ | 8.6% | 5.5% | 0.64 | 71.4% | 11.2% | 5.9% | **advisory only** |
| broad exception handlers 2+ | 1.5% | 0.9% | 0.57 | 73.9% | 2.0% | 0.9% | no signal |

How to read this:

- Mixed categories, duplicated names and intra-PR clones are more common in AI-flagged PRs **and** in
  merged PRs. They describe larger, well-structured changes (a feature with tests, docs and config),
  not rejected ones. Blocking on them would mostly hit good PRs.
- "No tests" is common in non-AI PRs (8.2%) and rare in AI-flagged ones (1.9%). Agents tend to add
  tests. The signal is about review risk, not authorship, which is what we want.
- Small UI changes (`.tsx`) showed up as "source without tests" because their tests live in a
  different layout. Test detection must be per ecosystem.

### 3.3 Caveats

- The patch sample is not random. Very large PRs often have no stored patch, so closed PRs with 50+
  reviewable files appear as 0 here. Use the full index (3.1) for size, and the patch sample only for
  shape signals.
- 63 patches were truncated by GitHub; their facts are lower bounds.
- The whitespace-only heuristic compares sorted added and removed lines, so moved blocks also count.
  The engine will use `git diff -w` instead.
- "Closed" mixes rejected, abandoned and superseded PRs. A signal with lift above 1 is a review-cost
  indicator, not proof of a bad PR.
- AI-flagged means disclosed or labelled. P16 says disclosure is unreliable, so AI versus not-AI
  columns are indicative only.
- Keyword theme counts over 1,519 list messages and 1,292 issue comments (second script) are a reading
  index, not a measurement. Section 2 is based on reading the threads.

## 4. External research

- Each added file lowers the odds a PR gets any review comment by about 8.7%
  ([arXiv 2609.22610](https://arxiv.org/abs/2609.22610v1)).
- Larger agent PRs merge less often ([arXiv 2602.19441](https://arxiv.org/html/2602.19441v1)).
- Agent PR descriptions often disagree with the diff
  ([arXiv 2601.04886](https://arxiv.org/html/2601.04886v2)), which matches P7.
- Agents leave duplicated logic in 50.8% of task chains by turn 5
  ([arXiv 2609.35357](https://arxiv.org/abs/2609.35357)).
- Copy and paste rose from 8% to 18% of changed lines while moved code fell below 10%
  ([GitClear](https://gitkraken.gitclear.com/recent_ai_developer_productivity_code_quality_research)).

The research supports reuse detection in general. Our Airflow evidence does not yet show it as a
maintainer pain, so reuse stays advisory until a project measures it.

## 5. Mapping pain to owner

| Pain | AIV (deterministic, per PR) | Magpie or GitHub (queue, people, judgement) |
|---|---|---|
| P1, P2 queue and capacity | routing tier and label so humans spend time on likely-mergeable PRs | queue ranking, CODEOWNERS, review rotation |
| P3, P5, P6 nobody read it, no why | template sections present and non-empty; linked issue | whether the why is coherent; whether the author owns it |
| P4 bulk authors | none | GitHub open-PR cap (adopted), Magpie author stats |
| P7 description mismatch | numeric test claims vs test changes in the diff; "added tests" with no test files | wording and intent mismatch |
| P8 untested changes | `untested-source-change`, per area (providers) | whether tests are meaningful; system-test evidence quality |
| P9 duplicated tests | exact and renamed test-body clones against the suite (advisory) | test placement and quality |
| P10 unrelated changes | files outside the area implied by title or labels (advisory) | confirming the change is unrelated |
| P11 several PRs per issue | none (needs other PRs) | Magpie triage |
| P12 large PRs | budgets by author tier and area, file classification, override label | split decisions |
| P13 bot noise | one short comment, fix brief, labels; no repeated reviews | Copilot policy |
| P14, P16 templates and disclosure | required headings, trailer vs checkbox consistency, instruction-file invariants | model-based compliance |
| P17 CI cost | `ci-change-by-non-collaborator` keeps such PRs advisory-flagged for draft | fork CI ("your PR, your CI") |
| P18 commit churn | none per run | Magpie history |
| P19 docs bloat | docs lines budget (advisory) | value judgement |
| P20 commit hygiene, behind base | commit message rules, unlinked author, commits behind base | none |

## 6. Consequences for the design

Applied in design v2:

- Remove `mixed-concerns`. Replace it with `infra-with-source` and `lockfile-change` (advisory).
- Add `untested-source-change` with per-ecosystem test globs and per-area overrides.
- Add an author tier (`meta.author_association`) and area budgets, so a project can encode "non-collaborator
  core PRs under N lines excluding tests" while collaborators keep a larger budget.
- Add `description-claims-mismatch`, `template-compliance`, `commit-hygiene`, `behind-base` and
  `ci-change-by-non-collaborator`.
- Keep reuse findings advisory, add a test-duplicate variant, and exclude test files from
  `dup_defs`-style name checks.
- Calibrate every policy on merged PRs first: a policy may move to `fail` only if it flags at most 2% of
  merged PRs and a maintainer records the decision.

## 7. Can deterministic signals catch 50% of AI slop?

Measured with `benchmarks/airflow/scripts/measure-slop-signal-recall.py` (report
`benchmarks/airflow/reports/slop-signal-recall-latest.md`). The corpus has no human-labeled slop set,
so the proxy is AI-flagged human Airflow PRs closed unmerged (1,095 PRs). Controls: 3,527 AI-flagged
merged PRs and 8,446 merged human PRs.

| Signals allowed | Merged PRs flagged (budget) | Slop proxy caught |
|---|---:|---:|
| per-PR gate signals only | 2% | 3.3% |
| per-PR gate signals only | 10% | 21.1% |
| per-PR gate signals only | 20% | 30.5% |
| gate plus project history | 2% | 9.2% |
| gate plus project history | 10% | 23.0% |
| gate plus project history | 20% | 43.0% |

Answer: **no, not at an acceptable false-flag rate.** Reaching 43% of the proxy means flagging 1 in 5
merged PRs, and no combination reached 50%.

Why, according to research on agent PRs:

- Most rejections are not code defects. In 562 rejected agent PRs, 38% were abandoned or never reviewed,
  23% were duplicates of another PR, and 17% failed CI or tests
  ([Ehsani et al., arXiv 2601.15195](https://arxiv.org/html/2601.15195)). In 353 rejected agent PRs, only
  35.7% were clear agent failures; 31.2% were workflow reasons and 33.1% had no visible rationale
  ([arXiv 2605.22534](https://arxiv.org/html/2605.22534v1)). Rejected-AI is therefore a loose proxy,
  and a perfect code check would still miss most of it.
- The strongest predictors are outside the diff: CI results, reviewer engagement, author history and
  duplicate PRs ([arXiv 2602.19441](https://arxiv.org/html/2602.19441v1),
  [arXiv 2601.18749](https://arxiv.org/html/2601.18749v1)). Models that reach high accuracy use
  metadata and text features learned per dataset ([arXiv 2607.12057](https://arxiv.org/abs/2607.12057v1)),
  which is not a deterministic, explainable gate.
- Several signals that "look like AI" (agent-default `## Summary` / `## Test plan` sections, bursts of
  PRs) flag authorship and activity, not low quality: author bursts flag 45.6% of merged PRs.

What the measurement supports:

1. **Per-PR gate (AIV):** high-precision blocks only (`mega-diff`, objective invariants), with the
   size, test and description policies as warnings. This catches a small share of slop with almost no
   false blocks.
2. **Project history (Magpie or a GitHub query):** `poor-track-record` and `no-merge-yet-and-burst` flag
   about 1% of merged PRs each and lift recall most per false flag. `competing-open-pr` and
   `issue-already-fixed` cover the duplicate-PR cause that research ranks second.
3. **After the PR opens:** CI failures and author silence (the top two rejection causes) are deterministic
   but only exist after opening; they belong in triage (Magpie), not in the PR-time gate.

The honest product claim: deterministic checks block objective defects and accidental mega diffs with
near-zero false blocks, and route the rest. They do not catch half of AI slop on their own.

## 8. JEV (model judgment) on Amazon Bedrock, alone and with AIV

Measured with `benchmarks/airflow/scripts/run-jev-bedrock-eval.py` (report
`benchmarks/airflow/reports/jev-bedrock-eval-latest.md`). The real `jev` gate ran against a local
System One adapter backed by Bedrock (`integrations/jev-bedrock-adapter`, Claude Sonnet 4.5,
temperature 0) with six questions: description mismatch, unrelated changes, unfinished code, missing
why, unnecessary code, likely broken. AI-disclosure lines were removed from descriptions. Sample: human
Airflow PRs with stored patches, 118 slop proxy, 100 AI merged, 100 non-AI merged, 60 non-AI closed.
382 calls, about 2.0M input and 0.12M output tokens (roughly $8 at list price).

| Layer (JEV at P(yes) 0.7) | Slop proxy | AI merged | Non-AI merged | Non-AI closed |
|---|---:|---:|---:|---:|
| AIV deterministic (gate plus history) | 30.5% | 19.0% | 18.0% | 26.7% |
| JEV alone | 12.7% | 4.0% | 14.0% | 41.7% |
| AIV plus JEV | 41.5% | 23.0% | 26.0% | 55.0% |

At P(yes) 0.9, JEV flags 2.5% of the slop proxy and 0% of merged PRs, so AIV plus JEV gives 32.2% with
about 18 to 19% of merged PRs flagged (all by the deterministic layer).

Findings:

1. **JEV adds about 11 points over AIV**, reaching 41.5% of the slop proxy, but the merged false-flag rate
   rises to about 25%. 50% was not reached.
2. **JEV flags rejected non-AI PRs far more than rejected AI PRs** (41.7% vs 12.7%). Rejected AI PRs look
   clean to the model: complete, motivated, on topic. This matches the "superficial competence" property
   of slop, and the research finding that most rejected agent PRs fail for workflow reasons (duplicates,
   abandonment, CI) rather than visible code faults.
3. **The most useful question is `missing_why`**; `unnecessary_code` never reached 0.7. Question wording
   was not tuned, because tuning on this same sample would overfit.
4. **JEV is best used as a reviewer aid**, kept advisory, as designed. At 0.9 it never flagged a merged PR
   in this sample, so a high threshold is a safe way to surface its strongest calls.

Caveats: the sample is small (118 slop-proxy PRs, so about plus or minus 9 points at 95%), only PRs with
stored patches are included, and the proxy still over-counts slop (section 7).
