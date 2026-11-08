# Change policy engine: design (v2)

Status: **implemented (phase 1)** in `aiv-plugin-change-policy`, default off, calibrated on 1,991 stored
PRs (section 14) and the full 15,575-PR Airflow index (section 15). Not yet built: reuse facts (4.6),
phase 2 (4.7, section 6), `git.unlinked_author_commits`.
Author: Viquar Khan.

Goal: let a project state, in one versioned YAML file, what a reviewable pull request looks like
(size budget by author and area, tests for source changes, description consistent with the diff,
template and commit rules), and have AIV enforce it deterministically before a maintainer spends time
on it.

v2 changes are driven by [MAINTAINER-PAIN-AND-PR-PATTERNS-ANALYSIS.md](MAINTAINER-PAIN-AND-PR-PATTERNS-ANALYSIS.md):
`mixed-concerns` is removed (it fires on 24.5% of merged PRs), reuse findings are advisory only, and
seven policies are added for pain that maintainers actually raised.

## 1. Problem and evidence (short form)

- Review capacity is the bottleneck. Airflow's contributor queue went from about 260 to about 870 PRs in
  2026; maintainers say 80 to 90% of triage is deterministic.
- Size only matters at the extreme: merge rate is flat up to 99 files and 46.2% at 100+ files, but 92 of
  those 199 PRs were merged. Size is a budget with classification and an override, never a blanket block.
- The strongest patch signal is source change without tests (lift 2.13, merge rate 43.0% vs 61.7%).
- Mixed file categories, intra-PR clones and duplicated function names are **more** common in merged PRs.
- Maintainers raised fabricated descriptions (22 tests claimed, 7 test lines), missing "why", template
  non-compliance, commit hygiene, CI changes from outsiders and untested provider changes.

Full numbers, caveats and sources are in the analysis document.

## 2. Principles

- **Facts, then policy.** Collectors compute deterministic facts. Policies are declarative conditions
  over facts. No policy runs code or calls a model.
- **Same input, same answer.** Facts come only from pinned base and head commits, the protected policy,
  and PR metadata supplied by CI.
- **Opt-in and calibrated.** Gate default-off. Every pack policy starts at `warn`. A policy moves to
  `fail` only after it flags at most the project's merged-PR ceiling (suggested 2%) and a maintainer
  records the decision (section 9).
- **Explainable and quiet.** Each finding names the policy, facts and thresholds, and how to fix or
  override. Output goes into the existing single AIV comment and fix brief, never a new bot thread
  (maintainer pain P13).
- **No authorship claims.** Policies describe changes, never who or what wrote them.
- **Per-PR only.** Queue-level and people-level questions (author PR volume, duplicate PRs for one
  issue, engagement, reputation) stay with Magpie and GitHub (section 10).

## 3. Architecture

```text
             trusted policy (.aiv/change-policy.yaml from the base ref, packs from the jar)
                                        |
Diff + git + workspace + PR metadata --> Fact collectors --> Facts (JSON, deterministic)
                                        |                     |
                       other gates' rule hits (phase 2)       v
                                        +------------> Policy evaluator --> findings policy.<id>
                                                              |                 |
                                                      risk score (optional)   rule_actions, overrides
                                                              v                 v
                                                     labels / routing     JSON, SARIF, PR comment, fix brief
```

New module `aiv-plugin-change-policy`, gate id `change-policy`, default-off. Reuse facts come from a
shared `aiv-analysis` library also used by the `duplication` gate. Existing `cohesion` and
`duplication` behaviour and the benchmark baseline do not change.

## 4. Facts

Every fact has a stable name, type and definition. Collectors are pure functions of the diff, git
objects for base and head, the head tree and metadata. A fact that cannot be computed in this run is
`unknown` (section 5).

### 4.1 File classification (first; every other fact can filter on it)

Each changed path gets one **category** from ordered globs (pack defaults, project overrides):
`source`, `test`, `docs`, `config`, `ci`, `build-infra`, `dependency-manifest`, `lockfile`, `generated`,
`vendored`, `migration`, `ai-instruction`, `binary`. Each path also gets an **area** from the project's
`areas` map (for example `providers`, `core`, `ui`, `chart`), falling back to the first `area_depth`
directory segments.

Change kinds: `added`, `modified`, `deleted`, `renamed`, `rename-only` (similarity 100%),
`mode-only`, `whitespace-only` (empty with `git diff -w`).

A file is **reviewable** when its category is not in `non_reviewable_categories` (default `lockfile`,
`generated`, `vendored`, `binary`) and its kind is not `rename-only`, `mode-only` or `whitespace-only`.

Test detection is per ecosystem. The pack ships globs for Python (`tests/**`, `test_*.py`), Java
(`src/test/**`, `*Test.java`, `*IT.java`), Go (`*_test.go`), JS/TS (`*.test.*`, `*.spec.*`,
`__tests__/**`), and projects add their own (for example Airflow UI tests or provider system tests).
The analysis showed UI `.tsx` changes misread as untested without this.

### 4.2 Size and scope

| Fact | Meaning |
|---|---|
| `files.changed`, `files.reviewable` | all changed paths; reviewable subset |
| `files.added`, `files.deleted`, `files.renamed` | by change kind |
| `files.by_category.<cat>`, `files.by_area.<area>` | counts |
| `lines.changed`, `lines.added`, `lines.deleted` | numstat over reviewable files |
| `lines.source_changed`, `lines.test_changed`, `lines.docs_changed` | by category |
| `lines.non_test_changed` | reviewable lines excluding test files (the Airflow "excluding tests" budget) |
| `lines.total_changed` | numstat over every changed path, including generated and vendored files (raw diff size) |
| `code.large_new_files` | new source files with 500+ added lines |
| `code.max_function_lines` | longest run of added lines after a new function start (Python, Go, JS/TS, Kotlin, Rust), upper bound |
| `code.stub_functions` | new functions whose first statement is `pass`, `...`, `raise NotImplementedError`, `todo!()` and similar |
| `scope.areas`, `scope.area_list` | distinct areas over reviewable files |
| `churn.whitespace_only_files` | files whose only change is formatting |
| `churn.max_file_share` | largest share of `lines.changed` in one file |

### 4.3 Metadata (from CI; `unknown` in local runs)

| Fact | Meaning |
|---|---|
| `intent.class` | title against the pack's intent map: `typo`, `docs`, `fix`, `bump`, `test`, `refactor`, `feature`, `unknown` |
| `meta.labels` | PR labels at evaluation time |
| `meta.author_association` | GitHub value: `OWNER`, `MEMBER`, `COLLABORATOR`, `CONTRIBUTOR`, `FIRST_TIME_CONTRIBUTOR`, `FIRST_TIMER`, `NONE` |
| `meta.author_tier` | project mapping of association, default `maintainer` (OWNER, MEMBER, COLLABORATOR) or `external` |
| `meta.is_draft` | PR is a draft |
| `meta.linked_issue` | body or title matches a configured pattern (default `#\d+` with `fixes`/`closes`/`related`; projects may add Jira-style keys) |
| `meta.body_words` | words in the body outside template boilerplate and HTML comments |
| `template.sections_missing` | required headings (from config) that are absent or empty |
| `claims.tests_count` | largest number of tests the body says were **added** ("added N tests", "N new tests", "N tests were added"); counts of tests run do not count |
| `claims.tests_added` | body says tests were added or updated |
| `claims.tests_overclaim` | `claims.tests_count - tests.functions_added` when a count is claimed, else 0 |
| `claims.ai_disclosure_mismatch` | a filled `Generated-by:` trailer while the "Yes" disclosure box is unchecked |

### 4.4 Git history (from the checkout; requires enough fetch depth)

| Fact | Meaning |
|---|---|
| `git.commits` | commits in `base..head` |
| `git.behind_base` | commits on the base branch not in head (`git rev-list --count head..base`) |
| `git.commits_without_body` | commits whose message has only a subject line |
| `git.conventional_prefix_commits` | subjects matching a configured prefix pattern (default `^(feat|fix|chore|docs|refactor|test|ci)(\(.+\))?!?:`) |
| `git.unlinked_author_commits` | commits whose author email matches a configured unlinked pattern (for example no `noreply.github.com` and not in the allow list); GitHub-side linkage is not checked |

If the clone is too shallow to compute a git fact, it is `unknown` and the facts note says which depth is
needed. It never fails closed on depth alone.

### 4.5 Tests

| Fact | Meaning |
|---|---|
| `tests.files_changed` | test files changed |
| `tests.functions_added` | added test function or method definitions (per-ecosystem patterns: `def test_`, `@Test`, `func Test`, `it(`/`test(`) |
| `tests.ratio` | `lines.test_changed / max(1, lines.source_changed)` |
| `tests.source_areas_without_tests` | areas with 1+ source line changed and no test file changed in the same area |

### 4.6 Reuse (shared `aiv-analysis` library, advisory only)

| Fact | Meaning |
|---|---|
| `reuse.exact_clones` | added blocks of `min_lines` normalized lines that already exist elsewhere in the repo |
| `reuse.renamed_clones` | same, after identifiers and literals are normalized (Type-2) |
| `reuse.test_clones` | added test bodies that match an existing test body (maintainer pain P9) |
| `reuse.reinvented_functions` | new non-test functions whose normalized body matches an existing function |
| `reuse.parallel_modules` | new files whose name matches an existing util or helper module |

Intra-PR clones and duplicated function names inside the PR are **not** facts in v2. They were more
common in merged PRs (analysis section 3.2).

### 4.7 Other gates (phase 2)

`hits.<rule-id-glob>`: count of findings from gates that already ran (for example `hits.invariant.*`).
Needs the orchestrator post-pass in section 7.3.

## 5. Policy language

YAML with structured conditions only. `all`, `any`, `not`, and leaf comparisons on a fact with `eq`,
`ne`, `gt`, `gte`, `lt`, `lte`, `in`, `not_in`, `contains`. `budgets` give per-tier and per-area
limits without writing one policy per combination.

```yaml
# config of the change-policy gate entry in .aiv/config.yaml (pinned from the protected base ref)
pack: oss-maintainer
classification:
  generated: ["**/generated/**", "**/*_pb2.py", "**/*.min.js"]
  build-infra: ["dev/breeze/**", "scripts/ci/**"]
  test: ["airflow-core/src/airflow/ui/**/*.test.tsx", "providers/*/tests/system/**"]
areas:
  providers: ["providers/**"]
  core: ["airflow-core/src/**"]
  ui: ["airflow-core/src/airflow/ui/**"]
budgets:                     # most specific match wins: tier+area, then tier, then default
  default:  { files: 50, lines: 1500 }
  external: { files: 30, lines: 800 }
  external.core: { non_test_lines: 200 }
template:
  required_sections: ["Why", "How was this tested"]
overrides:
  labels: [large-change-approved]
  per_policy: { untested-source-change: [tests-not-applicable] }
policies:
  - id: over-budget
    when: { fact: budget.exceeded, eq: true }
    action: warn
    overridable: true
    message: "{budget.detail} for {meta.author_tier} PRs in {scope.area_list}. Split the PR, or ask a
              maintainer to apply large-change-approved."
```

Pack policies (all `warn` by default):

| Policy | Condition (pack default) | Pain | Evidence |
|---|---|---|---|
| `over-budget` | any applicable budget exceeded | P12 | 100+ files merge 46.2% |
| `small-intent-large-diff` | `intent.class` in typo, docs, fix, bump and `files.reviewable > 10` | P10, P12 | 49.6% vs 70.0% merged; lift 1.36 |
| `untested-source-change` | `lines.source_changed >= 200` and `tests.files_changed == 0`; variant per area via `tests.source_areas_without_tests` | P8 | lift 2.13, merge 43.0% |
| `infra-with-source` | `files.by_category.ci >= 1` and `lines.source_changed > 0` (CI workflows only, section 14) | P17 | merge 51.9% |
| `ci-change-by-non-collaborator` | `meta.author_tier == external` and ci or build-infra files changed | P17 | Airflow "your PR, your CI" proposal |
| `lockfile-change` | lockfile changed without a dependency manifest change, or `intent.class` not `bump` | P10 | merge 51.0% |
| `formatting-churn` | `churn.whitespace_only_files >= 3` | P10 | merge 51.7% (heuristic) |
| `description-claims-mismatch` | `claims.tests_overclaim > 2` (claimed added tests minus added test functions), or `claims.tests_added`, no test files and source changed | P7 | lift 4.03, 0.4% of merged (section 14) |
| `template-compliance` | author is not a maintainer and (`template.sections_missing > 0` or `meta.body_words < 15`) | P6, P14 | lift 3.33 for the text rule |
| `mega-diff` (**fail**) | `files.changed >= 1000` | P12 | all 27 such Airflow PRs closed unmerged (section 15) |
| `small-fix-huge-diff` | small-change title and `lines.total_changed >= 5000` | P10, P12 | 32.3% merged vs 75.9% baseline (human PRs) |
| `large-new-code-unit` | a new 500+ line source file, a 150+ line new function, or 2+ stub functions | scaffolding | 47.1% merged when flagged vs 61.7% |
| `disclosure-consistency` | `claims.ai_disclosure_mismatch == true` (tool named in `Generated-by:`, box unchecked) | P16 | fabricated case had box unchecked |
| `commit-hygiene` | conventional prefixes or commits without body or unlinked authors above configured counts | P20 | proposed by Ash Berlin-Taylor |
| `behind-base` | `git.behind_base > 200` | CI readiness | examples 648 and 207 behind |
| `reused-code` | `reuse.reinvented_functions >= 1` or `reuse.renamed_clones >= 2` or `reuse.test_clones >= 1`; **advisory, cannot be promoted in the pack** | P9 | research; not raised by Airflow maintainers |

Language rules:

- Unknown facts, operators or params are configuration errors (exit 2).
- A fact `unknown` in this run makes the leaf `unknown`. A policy with an unknown leaf does not fire and
  is listed in the facts note, so local runs never fake a PR check.
- `action` is `fail`, `warn` or `off`. Existing `rule_actions` and `unsuppressible_rules` apply to
  `policy.<id>`.
- Messages reference facts and params with `{name}`; values are sanitised.

## 6. Risk score and labels (optional)

```yaml
risk:
  weights: { over-budget: 3, untested-source-change: 2, small-intent-large-diff: 2,
             description-claims-mismatch: 2, ci-change-by-non-collaborator: 2, template-compliance: 1,
             "hits.invariant.*": 3 }
  label_at: 4            # label the PR (for example aiv:needs-attention)
  fail_at: null          # null: the score never blocks on its own
```

Sum of weights of fired policies, reported with its parts. It feeds labels and the existing
`review-routing` tiers (score at or above `label_at` gives tier `none`, so no AI review is spent before a
human looks). Weights for policies with lift below 1 are rejected at load time when calibration data is
present (section 9).

## 7. Runtime integration

### 7.1 Inputs

- Diff, numstat, change types: already in `Diff` and `ChangedFile`.
- `git diff -w --numstat`, `git rev-list --count`, `git log --format` for base..head: new calls in
  `GitDiffProvider` under existing timeout and fail-closed rules (depth issues become `unknown`).
- Head tree for reuse: the duplication gate's bounded workspace walk.
- PR metadata: existing `AIV_PR_TITLE`, `AIV_PR_BODY`; new `AIV_PR_LABELS` (JSON array),
  `AIV_PR_AUTHOR_ASSOCIATION`, `AIV_PR_DRAFT`, all set by the Action from the event payload.

### 7.2 Overrides and trust

- Policy is read from the base ref (`--require-trusted-config`), so a PR cannot raise its own budget or
  reclassify its files.
- Override labels come from the event. Applying labels needs triage permission on GitHub. Projects that
  need more set `overridable: false` and use branch-protection bypass.
- Every override becomes a notice (`[POLICY-OVERRIDE] over-budget by label large-change-approved`) and an
  audit-log entry. The review ledger reports override rate per policy; a high rate means the policy is
  miscalibrated.

### 7.3 Orchestrator phase 2

Optional `PostGate` interface: runs after regular gates and receives their results, used for `hits.*`.
Without it, all facts in 4.1 to 4.6 still work.

### 7.4 Outputs

- Findings `policy.<id>` in JSON and SARIF, file `(diff)` or the specific file.
- One note `change-policy.facts` with the full fact snapshot.
- `aiv facts --diff <base> --head <head>` prints facts without evaluating policies.
- Fix brief sections: "How to split this PR" (over-budget, small-intent-large-diff) listing reviewable
  files by area; "Tests expected" (untested-source-change) listing source areas without tests.

## 8. Built-in packs

| Pack | Purpose | Defaults |
|---|---|---|
| `oss-maintainer` | general OSS | policies in section 5 at `warn`; budgets default 50 files / 1,500 lines, external 30 / 800 |
| `airflow` | tuned on the Airflow corpus | areas (providers, core, ui, chart), build-infra paths, UI and system-test globs, thresholds from calibration |
| `strict-agent` | repos that accept agent PRs only for small tasks | `over-budget`, `small-intent-large-diff` and `untested-source-change` at `fail`, overridable |

Packs ship in the jar and are versioned with the CLI.

## 9. Calibration and evaluation

| Dataset | Facts available | Used for |
|---|---|---|
| Airflow index, 15,575 PRs | files, lines, title, outcome | size budgets and intent; merged flag rate |
| 1,991 stored patches (Airflow plus 6 projects) | classification, tests, churn, claims from body | per-policy merged flag rate, lift, merge rate when flagged |
| Selected heads with checkout | everything incl. git and reuse | git and reuse facts, end-to-end verdicts |
| Designed fixtures in the labeled suite | everything | recall per policy, plus clean controls (big mechanical bump, rename-only refactor, feature with tests and docs) |

Known gap: the stored corpus has no `author_association`, so tier budgets cannot be calibrated from it
yet. The collector script will be extended to fetch it; until then tier budgets ship as documented
examples, not pack defaults.

For each policy the report states merged flag rate with a 95% interval, closed flag rate, lift, merge
rate when flagged, and fixture recall with exact bounds. Promotion to `fail` needs: fixture recall lower
bound at least 90%, merged flag rate under the project ceiling (suggested 2%), lift above 1, and a
recorded maintainer decision. A policy whose measured lift is at or below 1 is reported as
"not discriminating" and cannot be promoted.

Not claimed: a percentage of all AI slop caught, or that a flagged PR is AI-written.

## 10. What AIV does not do (Magpie or GitHub)

| Need | Owner |
|---|---|
| Open PRs per author, PRs per week | GitHub PR cap (Airflow adopted 5), Magpie stats |
| Several PRs for one issue | Magpie triage across the queue |
| Engagement, reputation, author response time | Magpie |
| Whether the "why" is coherent, whether tests are meaningful | human or model review (Magpie, Copilot after AIV) |
| Queue ranking and reviewer assignment | Magpie, CODEOWNERS |

AIV's role is the deterministic per-PR part that maintainers say is 80 to 90% of triage, with output
that Magpie can read (`change-policy.facts`, labels, fix brief).

## 11. Rollout

1. **Observe:** gate with facts only; publish `aiv facts` and the calibration report.
2. **Warn:** `oss-maintainer` at `warn`; findings in the AIV comment and fix brief.
3. **Enforce:** projects promote measured policies to `fail` with override labels.
4. **Phase 2:** `PostGate`, `hits.*`, risk score, routing integration.

## 12. Implementation plan and tests

| Step | Work | Tests |
|---|---|---|
| 1 | Classification, areas, per-ecosystem test globs; size, scope, tests facts | unit tests per fact and ecosystem; UI `.tsx` and provider fixtures |
| 2 | Git facts (`-w` numstat, behind base, commit messages) in `GitDiffProvider` | temp-repo tests; shallow clone gives `unknown`; timeout fail-closed |
| 3 | Metadata facts: intent, template sections, claims, trailer vs checkbox, labels, association | table tests on real PR bodies from the corpus (including the 22-tests case) |
| 4 | Policy loader and evaluator, budgets resolution, unknown handling, messages | table tests per operator and budget precedence, error cases, sanitising |
| 5 | Gate, packs, overrides, notices, outputs, `aiv facts`, fix-brief sections | gate tests, `MainTest` end to end, JSON/SARIF schema tests; 100% line coverage kept |
| 6 | `aiv-analysis` reuse facts; move duplication gate onto it | per-language tests; duplication tests and benchmark unchanged |
| 7 | Calibration script over the corpora; fixtures in the labeled suite | separate denominators; lift and merged flag rate per policy |
| 8 | Docs, commented preset snippets, handoff and source appendix | `validate-docs.py`, appendix `--check` |
| 9 | Phase 2: `PostGate`, `hits.*`, risk score, routing | orchestrator tests; benchmark unchanged when off |

## 13. Decisions taken

1. **Routing stays separate.** `review-routing` keeps its own config and benchmark semantics. Policies
   can feed routing in phase 2 through `hits.*` and the risk score.
2. **Linked issues:** `#123` and GitHub issue URLs always count; projects add tracker keys through
   `linked_issue_patterns` (the Airflow pack does not need Jira keys any more).
3. **Overrides, both kinds:** `overrides.labels` apply to policies marked `overridable: true`;
   `override_labels` and `overrides.per_policy` apply to one policy only. A policy with neither cannot
   be overridden by label. Pack policies `over-budget`, `small-intent-large-diff` and
   `untested-source-change` are overridable.
4. **Budgets:** `oss-maintainer` keeps 50 files and 1,500 lines (the full Airflow index shows the
   merge-rate drop starts above 99 files). Tier budgets ship as documented examples only, because the
   corpus has no author association to calibrate them.
5. **`description-claims-mismatch` ships at `warn`.** Calibration (section 14) gives 0.4% of merged PRs
   flagged and lift 4.03 after two precision fixes.
6. **Config lives in the gate entry** of the pinned `.aiv/config.yaml`, not a second file. It inherits
   trusted-policy loading and `rule_actions` without new plumbing.

## 14. Calibration results (phase 1)

Run with the real gate through `benchmarks/airflow/scripts/run-change-policy-calibration.py`, output in
`benchmarks/airflow/reports/change-policy-calibration-latest.md`. 1,991 PRs with stored patches
(Airflow 1,148 with the `airflow` pack, six other projects with `oss-maintainer`): 1,105 merged and
686 closed unmerged, baseline merge rate 61.7%.

| Policy | Merged flagged | Closed flagged | Lift | Merge rate when flagged | Meets promotion test |
|---|---:|---:|---:|---:|---|
| `untested-source-change` | 3.2% | 7.4% | 2.35 | 40.7% | no, merged rate above 2% |
| `description-claims-mismatch` | 0.4% | 1.5% | 4.03 | 28.6% | yes |
| `small-intent-large-diff` | 1.7% | 2.2% | 1.27 | 55.9% | yes |
| `infra-with-source` | 1.1% | 1.3% | 1.21 | 57.1% | yes |
| `lockfile-change` | 0.3% | 0.4% | 1.61 | 50.0% | yes (6 PRs, too few to act on) |
| `formatting-churn` | 0.0% | 0.3% | n/a | 0.0% | yes (2 PRs, too few to act on) |
| `over-budget` | 4.3% | 3.1% | 0.72 | 69.1% | no, see note |
| template text rule, any author | 2.8% | 9.3% | 3.33 | 32.6% | no, merged rate above 2% |
| `disclosure-consistency` | 0.1% | 0.0% | n/a | 100% | no, 1 PR |

Notes:

- Any policy fired on 11.9% of merged and 21.4% of closed PRs. All pack policies stay `warn`; a project
  promotes one only after its own measurement.
- `over-budget` looks inverted here because the patch sample rarely stores very large closed PRs. On
  the full 15,575-PR index, 100+ file PRs merge at 46.2% against 70 to 83% below 100 files. It stays a
  budget with an override label, not a detector.
- `template-compliance` exempts maintainers, and the corpus has no author association, so the gate
  skips it; the calibration-only variant without the exemption is the row above.
  `behind-base`, `commit-hygiene` and `ci-change-by-non-collaborator` need git history or author
  association and were skipped on every PR.

Changes made because of calibration (each one is covered by a unit test):

1. Test-count claims count only tests **added** ("added 22 test cases"), not tests run ("127 tests
   passing", "1111 passed"). Merged flag rate went from 3.0% to 0.4%.
2. Offers ("happy to add tests", "will add tests") are not claims; `claims.tests_added` also requires
   source changes.
3. `disclosure-consistency` fires only for "tool named, box unchecked". A ticked box with the tool
   named inline was the normal Airflow answer and made the first version inverted (11.1% merged).
   Template placeholders `[Tool Name...]` are ignored.
4. `infra-with-source` uses CI workflow files only. Build-infrastructure files (dev tooling and its
   tests) routinely accompany merged source changes in Airflow. Lift went from 0.71 to 1.21.
5. `template-compliance` exempts maintainers: most merged hits were release, backport and CI-upgrade
   PRs with short bodies.
6. Scala, Kotlin and Ruby test file names were added to the default test globs.

## 15. Size and scaffolding policies (added after the PR pattern labeling)

The labeling run (`benchmarks/airflow/scripts/label-pr-slop-patterns.py`, report
`benchmarks/airflow/reports/pr-pattern-labels-latest.md`) showed two size patterns that separate
rejected PRs sharply. Three policies were added and calibrated, now also on the full 15,575-PR Airflow
index (the calibration script has an index tier that feeds real file counts, line totals, titles and
bodies to the gate).

| Policy | Full index, human PRs: merged flagged | Closed flagged | Merge rate when flagged | Stored patches: merge rate when flagged |
|---|---:|---:|---:|---:|
| `mega-diff` (fail) | 0 of 8,446 | 26 of 2,688 | 0.0% | n/a (none stored) |
| `small-fix-huge-diff` | 10 (0.1%) | 21 (0.8%) | 32.3% | 10.0% (all PRs) |
| `large-new-code-unit` | n/a | n/a | n/a | 47.1% (baseline 61.7%) |

- `mega-diff` is the only blocking pack policy: every one of the 27 Airflow PRs with 1,000+ changed
  files was closed unmerged, including bots and backports. Most were small fixes built on the wrong base.
  It stays overridable by label for real mass changes.
- `small-fix-huge-diff` uses 5,000 lines, not 1,000. The threshold sweep on human PRs gave: 1,000+
  lines 66.1% merged, 2,000+ 56.1%, 3,000+ 53.8%, 5,000+ 32.3%, 10,000+ 15.0% (baseline 75.9%).
- `large-new-code-unit` covers the scaffolding sub-signals that held up in the labeling (new 500+ line
  files, 150+ line functions, stub bodies). Nesting depth and duplication inside the PR were left out:
  they were more common in merged PRs.

With these policies the gate would block 0 merged and 27 closed PRs on the full index (`mega-diff`), and
0 PRs on the stored patches. Labeled, structural and selected-real benchmarks are unchanged
(TP 35, FN 0, FP 0, TN 15, advisories 5/5; structural 2/2 with 0 false blocks; #59957 TP, #70842 TN).
