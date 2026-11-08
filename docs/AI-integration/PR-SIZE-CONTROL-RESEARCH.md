# Controlling oversized AI pull requests: what the evidence says, and what AIV does about it

Written 6 Nov 2026. Sources are the AIV calibration corpus (15,575 Airflow PRs, 1,991 stored patches across seven projects) and the public research linked inline. Secondary summaries are marked as such.

## 1. The problem

An agent asked for a small fix returns a 30 to 50 file change. The reviewer cannot read it, so one of three things happens: a superficial approve, a close without review, or a long back-and-forth that the agent answers with more commits. All three cost the maintainer more than the change was worth.

## 2. What the data says about size

**Your own corpus (apache/airflow, 22 Oct 2025 to 22 Oct 2026):**

| files changed | merge rate |
|---|---|
| 1 | 82.7% |
| 2 to 5 | 73.8% |
| 6 to 19 | 80.5% |
| 20 to 49 | 76.9% |
| 50 to 99 | 71.9% |
| 100+ | 46.2% (92 of 199 still merged) |
| 1,000+ | 0 of 27 merged |

- A title that promises a small change (fix, typo, bump, docs) but touches 20+ files: 49.6% merged vs 70.0% for the same titles under 20 files.
- Small-change title with 5,000+ lines: 32.3% merged vs a 75.9% baseline. At 1,000+ lines it still merged at 66.1%, which is why the shipped threshold is 5,000.
- 200+ source lines with no test lines: 43.0% merged vs 61.7% baseline (lift 2.13). The strongest patch-level signal, but 41% of flagged PRs still merge.

Source: `aiv-integrity-gate/docs/AI-integration/MAINTAINER-PAIN-AND-PR-PATTERNS-ANALYSIS.md` sections 3.1 and 3.2; `results/change-policy-calibration-latest.json`.

**Public research:**

- Each added file lowers the odds that a PR receives any review comment by about 8.7% ([arXiv 2609.22610](https://arxiv.org/abs/2609.22610v1)).
- On 33,596 agent-authored PRs (AIDev dataset, MSR 2026): larger change size lowers merge odds even after controlling for which agent wrote it and whether a reviewer engaged. Reviewer engagement is the strongest predictor of merging; force pushes during review are penalised; adding more commits or more tests does not help on its own ([arXiv 2602.19441](https://arxiv.org/html/2602.19441v1)).
- Agentic PRs merge less often than human PRs: 54.4% vs 73.3% in one study ([arXiv 2610.03969](https://arxiv.org/html/2610.03969v1)); 45.2% vs 68.4% where a code-review agent was the only reviewer ([arXiv 2604.03196](https://arxiv.org/abs/2604.03196v1)).
- Only about 36% of rejected agent PRs are clear agent failures; the rest are workflow reasons or have no visible rationale ([arXiv 2605.22534](https://arxiv.org/abs/2605.22534v1)). Test failures and "someone else already fixed it" are the top causes of non-integration for fix PRs ([arXiv 2602.00164](https://arxiv.org/html/2602.00164v1)).
- Across 92 dedicated AI policies in the top 10,000 GitHub repositories: 83.3% permit AI; the most common countermeasures to slop are closing PRs (48.4%), banning users (20.8%) and disallowing autonomous agents (13.8%). Godot's policy presumes anything under 15 lines is trivial and treats larger changes by amount, complexity, contributor experience and area. llama.cpp and dspy tell autonomous agents, in CONTRIBUTING.md, to stop and not open PRs ([arXiv 2609.07542](https://arxiv.org/html/2609.07542)).
- Secondary summaries, not verified against the primary: a Linux Foundation 2026 survey is reported as finding maintainers estimate about 25% of incoming PRs are low-quality AI contributions, 31% for projects with more than 50 contributors ([aicybr summary](https://aicybr.com/blog/linux-foundation-ai-generated-pr-survey-maintainer-policy-guide)); the Node.js governance discussion on a 19K-line AI PR is reported with "enforce maximum PR sizes with minimal exceptions" as the top-voted comment ([bswen summary](https://docs.bswen.com/blog/2026-03-19-pull-request-size-limits/)).
- Older review research (SmartBear/Cisco study, Cohen 2006) found defect detection drops sharply beyond roughly 200 to 400 lines per review session. It predates AI but is the basis for most "small PR" norms.

**What this adds up to:** size is a review-cost problem, not a defect signal. Blanket file limits hit real work (half of Airflow's 100+ file PRs merged). The signals that do separate accepted from rejected changes are mismatch signals (small intent, huge diff; claims in the description the diff does not back) and extreme size (1,000+ files, 5,000+ lines on a "small fix"), plus facts outside the diff (reviewer engagement, duplicates, CI).

## 3. Four places to intervene

| Layer | Lever | Who owns it | Evidence |
|---|---|---|---|
| **Generation** | Tell the agent the cap before it codes: AGENTS.md / CONTRIBUTING.md with "one issue, one PR, under N files, under M lines, no drive-by refactors", and Godot-style "under 15 lines is trivial" | project policy file | arXiv 2609.07542; cheapest lever, but only works for agents that read the file |
| **PR open** | Deterministic budget by author tier and code area, title-intent vs diff size, mega-diff block, "how to split" guidance back to the author | AIV `change-policy` | your calibration above |
| **Triage** | Open-PR cap per contributor, draft-until-explained, duplicate-issue detection, CI-failure holds | GitHub settings, Magpie, AIV `triage replay` | Airflow adopted a 5-PR cap on 24 Sep 2026 and closed 210 PRs once |
| **Review** | Route by tier, require a linked accepted issue for newcomer large changes, stacked PRs | CODEOWNERS, AIV `review-routing`, triage rule `hold-large-newcomer-without-accepted-issue` | arXiv 2602.19441 (engagement is the lever) |

## 4. Does AIV solve it?

**Mechanism: yes.** `change-policy` already has everything needed to enforce a size budget at PR time:

```yaml
- id: change-policy
  enabled: true
  config:
    pack: oss-maintainer
    budgets:
      default:  { files: 50, lines: 1500 }      # maintainers
      external: { files: 30, lines: 800 }       # authors without write access
      external.core: { non_test_lines: 200 }    # the Airflow "under 200 non-test lines in core" idea
    overrides: { labels: [large-change-approved] }
    policies:
      - { id: over-budget, action: fail }           # block when a budget is exceeded
      - { id: small-intent-large-diff, action: fail } # "fix"/"typo"/"docs"/"bump" title but 10+ reviewable files
      - { id: mega-diff, action: fail }               # already fail: 1,000+ files
```

Policy is read from the protected base ref, so the PR cannot raise its own budget. Every override by label becomes a `[POLICY-OVERRIDE]` notice and an audit entry, and a high override rate tells you the budget is miscalibrated. The `strict-agent` pack ships exactly this posture at 20 files / 600 lines for repositories that only accept agent PRs for small tasks.

Two things the design document describes that I checked are **not** in the code yet: the fix brief (`--output-fix-brief`, `FixBriefWriter.java`) lists findings but has no "how to split this PR by area" section, and `meta.linked_issue` is collected as a fact (`FactCollector.java`) but no shipped pack has a policy on it. Both appear in section 6.

**Calibration: partial, and the repo says so.** Tier budgets (external vs maintainer) are the right design, but the stored corpus has no `author_association`, so they ship as documented examples rather than calibrated defaults (`CHANGE-POLICY-ENGINE-DESIGN.md`, "Known gap"). The only size cut with zero false blocks in the data is 1,000+ files. `over-budget` at 50 files fired on 4.3% of merged and 3.1% of closed patches (lift 0.72), which is why it ships as `warn`.

**Ceiling: it does not make a 50-file PR reviewable; it refuses it.** That is the right outcome for an external agent PR against a "small fix" title; the over-budget message already says "split it into smaller PRs", and the per-area split suggestion is the next thing to add. It is the wrong outcome for a maintainer's provider migration. Hence tiers.

**Honest verdict:** AIV gives you a deterministic, tamper-proof, auditable size budget with per-tier and per-area limits and an override path. That is the PR-open layer, and it is built. It does not touch the generation layer (the agent must be told the cap), it only replays the triage layer offline today, and its tier budgets are not yet calibrated on real author data. On its own it will not stop oversized agent PRs from being created; it will stop them from reaching a reviewer unread, and it will tell the author why.

## 5. Recommended rollout for a repository like Airflow

1. **Generation:** add the budget to AGENTS.md / CONTRIBUTING.md in agent-readable form: "Pull requests from contributors without write access: at most 30 files and 800 changed lines, at most 200 non-test lines under core; one issue per PR; do not reformat or refactor files you were not asked to touch."
2. **PR open, week 1 to 4:** `change-policy` with `over-budget`, `small-intent-large-diff`, `untested-source-change` at `warn`, `mega-diff` at `fail`. Collect `change-policy.facts`.
3. **Measure:** of PRs flagged over-budget, how many merged anyway (false-block rate), and the override rate per tier. The repo's own rule: promote only when the merged flag rate is under 2%.
4. **PR open, promote:** `over-budget` to `fail` for `external` only, keep `warn` for maintainers; keep the override label. Expect to revisit the numbers once `author_association` is in the corpus and tier budgets can be calibrated.
5. **Triage:** enable GitHub's open-PR cap for non-collaborators (Airflow: 5); run `aiv triage replay` against the last quarter to see what `hold-large-newcomer-without-accepted-issue` (500+ lines, no accepted issue) would have proposed before turning anything live.
6. **Review:** `review-routing` label so maintainers see tier before opening the diff.

## 6. What to build next, in order of leverage

1. Add `author_association` to the corpus collector and calibrate tier budgets (the known gap).
2. Make the split guidance concrete: add a "How to split this PR" section to `FixBriefWriter` that groups reviewable files by `scope.area_list` and suggests the first PR (designed in `CHANGE-POLICY-ENGINE-DESIGN.md`, not implemented).
3. Ship a policy on the existing `meta.linked_issue` fact: external PR over a size threshold with no linked, accepted issue is flagged (the triage replay has the equivalent rule; the PR-time gate does not).
4. A live, human-approved subset of triage actions (draft + comment) once replay precision is measured.
5. Ship a short AGENTS.md snippet alongside each preset so the generation layer and the gate say the same numbers.
