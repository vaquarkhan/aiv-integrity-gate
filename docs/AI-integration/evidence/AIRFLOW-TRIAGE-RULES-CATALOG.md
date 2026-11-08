# Jarek Potiuk on PR triage: catalog of rules, workflow, numbers and tools

Source corpus (local, read-only): `aiv-airflow-bench/corpus/ai-year/` (dev@airflow.apache.org, 2025-10 to 2026-10).

Method:
- Python scripts in %TEMP% pulled every message whose `from` contains "Potiuk" or "Jarek" from `mail-index.jsonl` (28 MB) and `mail-threads/*.json` (264 threads), deduplicated by message id. That gave 962 messages.
- Quoted lines starting with ">" and "On ... wrote:" blocks were stripped.
- 264 messages matched triage-related subjects or bodies. 197 were read in full after release-vote and unrelated technical threads were pruned. A second keyword sweep over the rest found 5 more relevant messages.
- `issues-all.jsonl` (2,427 records), `issues/*.json` (247) and `prs/*` (493) contain 206 + 23 comments by `potiuk`. None of them are triage comments. They are technical issue reports, some signed "Drafted-by: Claude Code (Opus 4.x); reviewed by @potiuk before posting". So this catalog is based on the mailing list only.

Conventions:
- Every item cites the thread subject (shortened) and the message date (UTC, from the mail header).
- Quotes are verbatim and at most 25 words. Where the original had an em dash it is written as " - ".
- Status values: **implemented** (he says it runs or was run), **adopted** (lazy consensus or vote passed), **proposed** (put forward, not adopted or not yet run), **rejected/reverted**.
- Type values: **DET** (deterministic), **LLM** (LLM judgment), **HUMAN** (triager or maintainer decision), **SETTING** (GitHub or .asf.yaml configuration).
- Caveat: in some of his replies, quote markers from the other person were lost, so a leading paragraph can be someone else's text. Where that matters it is flagged below.

---

## 1. Deterministic checks and rules

### 1.1 Scope and selection

| ID | Condition / threshold | Action | Type | Status | Source (subject, date) | Quote |
|---|---|---|---|---|---|---|
| S1 | PR author is not a committer and not a collaborator ("non-collaborator") | PR enters auto-triage. Collaborators are not auto-triaged. They self-apply the "ready for maintainer review" label | DET | adopted (LC passed 2026-03-10) | Make our "ready for review" expectation more explicit and stricter, 2026-03-03; LC Implement auto-triage and "Drafting", 2026-03-06 / 2026-03-10 | "This approach is only for non-collaborators. For collaborators: we might have just one expectation - mark your PR with "ready for maintainer review"" |
| S2 | PR is from a non-collaborator AND not yet triaged, OR already triaged and needs follow-up (Draft / Not responded / Closed) | Included in the triage run. Everything else is skipped automatically | DET | implemented | Current auto-triage stats/learnings, 2026-04-03 | "Triage is only for issues from non-collaborators and for already triaged issues that might need some action (like Draft / Not responded / Closed)." |
| S3 | `--reviews-for-me` flag: PR where CODEOWNERS auto-assigns you as reviewer, or where you are mentioned | Restricts the triage or review set to that maintainer | DET | implemented (needed PR #64669) | Current auto-triage stats/learnings, 2026-04-03 | "This will only select issues where CODEOWNERS automatically sets you as a reviewer or where you are mentioned." |
| S4 | Filter by area (area:* label) or specific provider | Run triage or review on one area only. Dashboard "pressure by area" ranks the top 8 areas | DET | implemented | Active approach to fighting AI slop, 2026-03-02; Maintainer PR dashboard prototype, 2026-05-06 | "we can triage PRs by "area", specific providers, etc." |
| S5 | Triager runs the tool (not on a schedule). Cadence: daily or every two days | Slows the Draft / Undraft ping-pong | HUMAN cadence | proposed, then practiced | Make our "ready for review"..., 2026-03-03 | "our goal should be to slow it down rather than respond immediately; for example, running it daily or every two days is a good idea." |
| S6 | Stats exclude bots and backports (and in the fixup study: merge commits from "update branch", cherry-picks, maintainer takeovers) | Reporting filter | DET | implemented (analysis) | Next step after the PR limit, 2026-10-05; Merging "almost" ready PRs, 2026-10-09 | "I compared PRs opened from January to Oct 4, excluding bots and backports." |

### 1.2 Per-PR deterministic quality gates (non-collaborator PRs)

| ID | Condition / threshold | Action | Type | Status | Source | Quote |
|---|---|---|---|---|---|---|
| D1 | Any required CI check failing (tests, static checks). Comment groups: "Kubernetes tests", "Provider tests", "Other failing CI checks", "Pre-commit / static checks" | Convert to Draft. Post a comment (later a folded status note) linking to the "Pull Request quality criteria" and per-check docs. Author un-drafts after fixing | DET | adopted (LC 2026-03-10), implemented | Active approach..., 2026-03-02; PR auto-triage recent stats, 2026-05-22 | "Automatically convert PRs that fail the criteria to Draft and explain the author that they should un-draft it only when the issues are fixed" |
| D2 | Static-check failure | Comment tells the author to run `prek run --from-ref main --stage pre-commit` (earlier version: `prek run --from-ref main`) and links `08_static_code_checks.rst` | DET | implemented | PR auto-triage recent stats, 2026-05-22; Meeting Notes dev call (teaser), 2026-03-11 | "Run prek run --from-ref main --stage pre-commit locally and fix anything that flags." |
| D3 | Branch is N commits behind `main`. Examples shown: 207 and 648. No numeric threshold stated | Note in the comment: some failures may come from the base branch, rebase and push again | DET | implemented | PR auto-triage recent stats, 2026-05-22; Meeting Notes, 2026-03-11 | "Note: Your branch is 207 commits behind main. Please rebase and push again to get up-to-date CI results." |
| D4 | Failing CI is "stale-main pollution" (the same failure happens on main or for others, and is already fixed on main) | Ask to rebase or rebase it, rather than blame the author. Proposed comment: "Please rebase now, the issue is fixed" | DET | proposed 2026-03-03, implemented by 2026-05-06 | Make our "ready for review"..., 2026-03-03; Maintainer PR dashboard..., 2026-05-06 | "we can likely add also an easy automated, deterministic check if the failure is happening for others." |
| D5 | Merge conflicts not addressed | Auto-draft. If the PR had the ready label, remove it | DET | implemented | Maintainer PR dashboard..., 2026-05-06; PR auto-triage recent stats, 2026-05-19 | "the queue of *bad* PRs (mergeable conflicts not addressed, abandoned drafts, missing CI signals) is mostly gone" |
| D6 | Missing CI signals (no CI result on the PR) | Auto-draft or rebase | DET | implemented | Maintainer PR dashboard..., 2026-05-06 | same as D5 |
| D7 | Unresolved review comments or threads on the PR | Extra triage signal (blocks "ready"). Added with new filtering options | DET | implemented (PR #63300) | LC Implement auto-triage..., 2026-03-10 | "I added more signals (unresolved review comments) and filtering options" |
| D8 | First-time contributor PR needs workflow approval | Tool prints the PR diff in the terminal. Triager confirms nothing suspicious and answers "y". Workflow run is approved. 20 approvals in the 2026-06-12 run | DET + HUMAN | implemented | Make our "ready for review"..., 2026-03-04; Update our AI coding policy..., 2026-06-12 | "my nice little tool will print the diff of the incoming PR on terminal and ask the triager to confirm that there is nothing suspicious" |
| D9 | Failed or flaky CI that only needs a rerun | "reruns" action (3 in the 2026-06-12 run) | DET + HUMAN | implemented | Update our AI coding policy..., 2026-06-12 | "reruns: 3" |
| D10 | All PR checks green AND guideline checks pass (D1-D7 clear, then the LLM check L1) | Apply label **"ready for maintainer review"**. Applying the label posts no message | DET + LLM | adopted, implemented | Active approach..., 2026-03-02; What do we do with unreviewed PRs, 2026-06-10 | "We apply label "ready for maintainer review" when both deterministic and LLM checks pass for the PR" |
| D11 | A "ready for maintainer review" PR starts failing (conflicts, failing tests, needs rebase) | Remove the ready label (automated "unlabeling") | DET | implemented | PR auto-triage recent stats, 2026-05-19 | "unless they start failing (Conflicts, rebases etc. - in which case the "ready for maintainer review" label is removed" |
| D12 | Exception to D11: a merit discussion (reviewer direction, consensus) has started on the PR | Keep the ready label. Do not unlabel for mechanical failure | DET | implemented (airflow-steward PR #232) | PR auto-triage recent stats, 2026-05-19 | "it should not be removed if there is a discussion is started on the merit of that PR - not on mechanical failures." |
| D13 | Otherwise, a PR already marked ready | Triage leaves it alone | DET | implemented | PR auto-triage recent stats, 2026-05-19 | "Generally the triage process does not touch "Ready for maintainer review" PRs" |
| D14 | Proposal for a CI step: first-time contributions are validated deterministically inside `selective-checks` / the "build info" job, failing with a clear statement | Fail CI with an explanation | DET | proposed | Update our AI coding policy..., 2026-06-09 and 2026-06-10 | "we can build it into "selective-checks" to "fail" and make a clear statement during failure." (the idea came from Amogh/Shahar) |

### 1.3 Author-level rules (counts, suspicious behaviour, PR limit)

| ID | Condition / threshold | Action | Type | Status | Source | Quote |
|---|---|---|---|---|---|---|
| A1 | Author has **more than 3 open PRs failing quality checks** | Close **all** PRs from that author. Label "closed because of multiple quality violations" | DET | proposed in PR #62682 (the LC of 2026-03-10 adopted the "broad" drafting process; details were left to testing) | Active approach..., 2026-03-02 | "or when the author has more than 3 open PRs failing quality checks." |
| A2 | Suspicious changes seen while deciding whether to run workflows for a new user | Close **all** PRs from that author. Label "closed because of suspicious changes" | HUMAN-confirmed, DET action | proposed in PR #62682 | Active approach..., 2026-03-02 | "We CLOSE all PRs from an author when we detect suspicious changes" |
| A3 | Author profile shown for every PR: PRs opened and closed, account age, where they contribute, links | Information for the triager (no automatic action) | DET | implemented | Active approach..., 2026-03-02 | "including author details (number of PRs opened and closed, account age, contribution locations)" |
| A4 | Open-PR cap for users **without write access** = **5** at a time (GitHub "limit concurrent open pull requests", `.asf.yaml` `creation_cap`). Committers unaffected. Drafts **not** counted yet (GitHub could not count them) | GitHub blocks new PRs over the cap | SETTING | adopted (LC passed 2026-09-24 19:00 CEST). Hard cap removed 2026-09-26 (INFRA-28454), so it is a **soft limit** until asfyaml is deployed | LC Limiting number of opened PRs, 2026-09-21; RESULT, 2026-09-24; Open PR limit is temporarily a soft limit, 2026-09-26 | "with the limit set to 5 - the most supported value in the discussion. Committers are not affected." |
| A5 | One-time closure (`dev/close_prs_over_open_pr_limit.py`): contributor without write access has **> 5** open PRs | Close the PRs over the limit. Comment with the reason and a link to `contributing-docs/32_open_pull_request_limit.rst`. Label "closed because of open PR limit". Ask the author to choose up to 5 to reopen | DET | implemented 2026-09-25 (210 PRs). Will re-run with the same rules once the cap is enabled | RESULT LC, 2026-09-24 and 2026-09-25; soft-limit announce, 2026-09-26 | "Contributors without write access who have more than 5 open PRs will have their open PRs closed" |
| A6 | Exception to A5: a maintainer already commented on or reviewed the PR. **Automated triage comments do not count** as engagement | PR stays open (about 70 in the dry run) | DET | implemented | RESULT LC, 2026-09-24 | "PRs where a maintainer is already engaged (commented or reviewed) stay open ... Automated triage comments do not count as engagement." |
| A7 | Bypass list for contributors exempt from the cap (for example an urgent fix) | Exempt from the cap | SETTING | proposed (infrastructure-asfyaml PR #135) | RESULT LC, 2026-09-24 | "A bypass list, so that we can exempt contributors where it makes sense (for example for an urgent fix)" |
| A8 | Closed PR cannot be reopened | Author pushes the branch under a new name and opens a new PR (with a human summary, via the GitHub UI) | DET guidance | implemented (in the guide) | RESULT LC thread, 2026-09-26; soft-limit announce, 2026-09-26 | "push branch with a new name and open the PR again." |
| A9 | Reaction after a closure (read by humans): no reopen means drive-by; instant reopen of minor PRs suggests automation ("OpenClaw etc."); reopening the right PRs and engaging means pay more attention | Human watch list. No automated action | HUMAN | proposed heuristic | LC Limiting..., 2026-09-21 | "they reopen some inconsequential ones immediately - we will know they are likely doing some automation OpenClaw etc. and watch them carefully" |
| A10 | Engagement buckets for prioritising contributors: **threads per open PR >= 1.5** = engaged; **all-time merge rate >= 40%** = healthy. A (net reviewers) > B (shippers) > C (low engagement and low merge rate) | Priority of attention (analysis only) | DET (Claude analysis he endorsed) | proposed heuristic | LC Limiting..., 2026-09-21 | "engagement = others' threads per open PR (≥1.5 counts as engaged), quality = all-time merge rate (≥40% healthy)." |
| A11 | Proposal: limit the **size** of non-collaborator PRs, for example **< 10 lines (excluding tests)** of core | CI rejects anything bigger | DET | proposed | What do we do with unreviewed PRs, 2026-06-30 | "<10 lines (excluding tests) of "core" contributions will be okay; anything bigger will be rejected by CI" |
| A12 | Proposal: cap the number of collaborator PRs open **per area** | CI rejects over the cap | DET | proposed | What do we do with unreviewed PRs, 2026-06-30 | "Limit the overall number of collaborator PRs that can be opened "per area" - this could be rejected by CI" |
| A13 | Possible future rule: large PRs from inexperienced contributors | Automated close | DET | proposed ("potential") | "scheduled" triaging ... Airflow as engine, 2026-09-10 | "potential to automate closing large PRs from inexperienced contributors" |

### 1.4 LLM-based checks (run only after the deterministic gates)

| ID | Condition / threshold | Action | Type | Status | Source | Quote |
|---|---|---|---|---|---|---|
| L1 | All deterministic criteria pass. The LLM then checks the PR guidelines (description, content, quality, presence of tests). Typically 15% or fewer of the PRs in a batch, 20 s to 2 min each | Pass: ready label (D10). Fail: Draft with a comment, after human review | LLM | implemented (Claude Code, Codex, Copilot. opencode and ollama possible) | Make our "ready for review"..., 2026-03-04; LC Implement auto-triage, 2026-03-11 | "LLMs check is fired only when all other easily and deterministically verifiable criteria are met." |
| L2 | Description does not match the code (example: claims "22 test cases", diff adds 7 lines) | "[ERROR] AI-Generated Code Signal" | LLM | implemented (teaser output) | Meeting Notes dev call 26 Feb, 2026-03-11 | "AI-Generated Code Signal: Description doesn't match code" |
| L3 | Gen-AI disclosure checkbox unchecked but strong AI signals (emoji formatting, fabricated test counts, narrating comments, overstated scope) | "[WARNING] Gen-AI disclosure": must be checked if AI was used | LLM | implemented (teaser output) | Meeting Notes, 2026-03-11 | "The Gen-AI disclosure checkbox is explicitly unchecked (`- [ ] Yes`), yet the PR exhibits multiple strong AI-generation signals" |
| L4 | Proposal: "maintainer time" metric. Will reviewing take much longer than it would take a maintainer to write the change? Calibrated per area (core vs providers) on 230+ open test cases | Close with kind feedback suggesting smaller fixes or other areas | LLM | proposed | Concrete, complete idea for next steps in our triage, 2026-07-09 | "use AI to help assess if a PR would take significantly more time to review than it would take a maintainer to create it." |
| L5 | Proposal: classify priority ("high-priority PR") from criteria the community agrees in the PR Quality Criteria | Prioritised review | LLM | proposed | What do we do with unreviewed PRs, 2026-06-10 | "If we reach a consensus on what a "high-priority" PR looks like, we could even use LLMs to help with the classification." |
| L6 | Root-cause and user-facing changelog wording (provider doc preparation skill) | Draft changelog text | LLM | proposed | VOTE Providers 2026-04-21 thread, 2026-04-23 | "instruct the agent to search for the root cause of the issue and present it in "user facing way"" |

### 1.5 Timers, stale and close rules

| ID | Condition / threshold | Action | Type | Status | Source | Quote |
|---|---|---|---|---|---|---|
| T1 | Observed path: Draft, then triage comment, then **no author response for more than 2 weeks** | Close (most of the 60% closed-unmerged followed this path) | DET | implemented (observed) | Current auto-triage stats, 2026-04-03 | "Draft -> Triage -> No response (more than 2 weeks) -> Close." |
| T2 | Triaged draft past a **7-day no-response window** | Closed by the skill ("stale-sweep"). 5 "stale-draft closes" in the 2026-06-12 run | DET | implemented | Maintainer PR dashboard..., 2026-05-06; Update our AI coding policy..., 2026-06-12 | "stale-triaged drafts past the 7-day no-response window" |
| T3 | Drafts with no activity | Pruned more aggressively by the stalebot | DET | proposed (part of the LC proposal) | Make our "ready for review"..., 2026-03-03 | "Drafts with no activity will be more aggressively pruned by our stalebot." |
| T4 | AIP-120: PR in `setup_required` or `awaiting_push` for `close_after_days` (set to a **7-day inactivity period** in the final AIP) | Close with a new comment. Reopening restarts the clock | DET | proposed. LC opened 2026-10-19, closes 2026-10-22 12:00 UTC | Run CI for external contributions in forks, 2026-09-19; Next step ... "your PR, your CI", 2026-10-15; LC AIP-120, 2026-10-19 | "PRs with no CI activity after a specified period are automatically closed with an explanation (and can be reopened later)." |
| T5 | Ready-label exception (D12) applies regardless of age | Do not unlabel | DET | implemented | PR auto-triage recent stats, 2026-05-19 | see D12 |
| T6 | Issue-assignment auto-unassign after N days (5, 10 or 15 suggested by others) | No timer. Rejected as brittle | n/a | rejected | Stop assigning unknown contributors, 2026-02-19 | "We would have to make a judgment call that 5 or 10 or 15 (or whatever) days is "enough" to unassign things" |
| T7 | AIP-120 status mirror refresh | Mirrored fork CI status is updated periodically, "e.g., every 15 minutes" | DET | proposed | Run CI for external contributions in forks, 2026-09-22 | "It will update periodically (e.g., every 15 minutes)." |

### 1.6 Communication and notification rules

| ID | Condition | Action | Type | Status | Source | Quote |
|---|---|---|---|---|---|---|
| C1 | Any triage comment | Must include: link to the PR quality criteria, a docs link per failed check type, a clear "what to do next", "not a rejection" wording, a note that the comment is AI-assisted and a human maintainer looks next, and a link to the process | DET template + LLM tailoring | implemented | PR auto-triage recent stats, 2026-05-22 | "link to our criteria, for each failed check type link to appropriate docs, clear expectation what to do next, note about AI contribution" |
| C2 | Comment attribution footer | "Drafted-by: <tool> (<model>); reviewed by @<maintainer> before posting". Formats separate pure automation from human-reviewed text (PR #65965). "Assisted By" vs "Generated-by" under legal discussion | DET | implemented | PR auto-triage recent stats, 2026-05-22; Triage + review AI assistance, 2026-04-28 | "Drafted-by: Claude Code (Opus 4.7); reviewed by @potiuk before posting" |
| C3 | Draft comment content | Stop listing the individual failed job names (PR #67322, to be upstreamed to Magpie) | DET | implemented in Airflow PR #67322 | PR auto-triage recent stats, 2026-05-22 | "the biggest change I see can be done quickly is to stop listing the failed job" |
| C4 | De-noising: instead of comments, draft the PR and fold the instructions into the PR body (the technique used for security issues) | Fewer notifications | DET | proposed 2026-06-10, "implemented de-noising" 2026-06-11 | What do we do with unreviewed PRs, 2026-06-10 / 2026-06-11 | "we could just turn the PR into a draft and "fold" the instructions into the PR itself." |
| C5 | Single updateable status block in the PR description: latest status and responsible party | Replaces repeated comments | DET | implemented (example PR #67790) | Update our AI coding policy..., 2026-06-12 | "I've replaced repetitive comments with a single, updateable description in the PR." |
| C6 | Action needed from the author / PR ready | Assign the author when action is needed, unassign once ready (drives notifications) | DET | implemented | Update our AI coding policy..., 2026-06-12 | "Authors are now assigned when action is needed and unassigned once ready" |
| C7 | Maintainer handles in generated text | A deterministic Python hook formats them as `@id` in backticks so nobody is pinged by accident | DET | implemented | Update our AI coding policy..., 2026-06-12 | "maintainer IDs are formatted correctly - with (`@id` in backticks) to prevent any accidental pings." |
| C8 | Other action types in a run: "request-author-confirmation" (4), "pings" (4), "violation folds (draft/comment)" (7) | Post those actions | DET + HUMAN | implemented | Update our AI coding policy..., 2026-06-12 | "request-author-confirmation: 4 ... pings: 4" |
| C9 | Ready-for-review PR waiting for a soft-committed steward (CODEOWNERS level 3) | Ping after some time, following the "gentle ping rules" | DET | proposed | Clarifying the role of CODEOWNERS, 2026-04-26 | "We could add automation to ping both committers and non-committers interested in point 3." |
| C10 | AIP-120 `awaiting_push` reached from `setup_required` | Post a **new** comment, because an edit would not notify | DET | proposed | Run CI ... forks, 2026-09-19 | "post a new comment asking to push again (an edit would not notify)" |
| C11 | Every post to a contributor | Human triager reviews it before posting (until confidence is high) | HUMAN | implemented | Make our "ready for review"..., 2026-03-03; LC Implement auto-triage, 2026-03-12 | "No comment, no message, no assessment posted to the contributor without conscious decision of the maintainer." |

### 1.7 AIP-120 "your PR, your CI" state machine (fork CI gate)

Status: design PR #73378 plus 5 stacked PRs (#74819 gate in ci-amd.yml, #74820 fork-mode selective checks, #74822 reconciler and scheduled monitor, #74823 `breeze ci audit`, #74825 docs). Everything ships switched off. Opt-in pilot first. LC opened 2026-10-19 and closes 2026-10-22 12:00 UTC. Sources: Run CI for external contributions in contributor forks (2026-09-18/19/22), Next step after the PR limit (2026-10-05/15), LC AIP-120 (2026-10-19). All DET.

| ID | Observed fork state | Draft | "ready for maintainer review" | Commit status | Comment |
|---|---|---|---|---|---|
| F1 | `setup_required`: fork workflow state `disabled_fork`, or the fork has no `ci-amd.yml` | draft | remove | error (red), link to the fork's Actions page | create or update: how to enable, with `gh workflow enable ci-amd.yml -R <fork>` and `breeze ci audit` |
| F2 | `awaiting_push`: enabled, no run for the head SHA | draft | remove | pending (yellow) | new comment asking to push again (on transition from F1) |
| F3 | `running` | draft | remove | pending (yellow), link to the run | update state and run link |
| F4 | `failed`: conclusion failure, cancelled or timed out | draft | remove | failure (red) | update with run link |
| F5 | `green`: conclusion success | undraft, but **only if the last convert-to-draft event was by github-actions[bot]** | add | success (green) | update: ready for review, run link |
| F6 | F1 or F2 for `close_after_days` (7 days) | unchanged | unchanged | unchanged | close with a new comment. Reopen restarts the clock |

Extra AIP-120 rules:
- F7 Scope: only contributors who are not committers, not collaborators and not PMC-approved stakeholder repos (for example forks used by Astronomer, AWS, Google). Runs from non-approved forks are stopped in apache/airflow. Quote: "Runs originating from non-approved forks are stopped in apache/airflow." (2026-09-18)
- F8 Trust: a green fork run is trusted only if the PR does not change what CI runs (`.github/`, `dev/breeze/`, `scripts/ci/` and similar). Otherwise the PR stays draft until a maintainer applies the "use project ci" label. Quote: "A green fork run is only trusted when the PR does not change what CI runs (.github/, dev/breeze/, scripts/ci/ and similar)." (2026-10-19)
- F9 Labels: "use fork ci" (a committer tries it on one PR) and "use project ci" (a maintainer forces project CI before merge). Pilot membership: `[pilot]` table in `.github/fork-ci-gate.toml` (GitHub login plus join date). (2026-10-15)
- F10 Fail-closed: the gate never falls back to project CI when it cannot decide. A PR that edits workflows to get around the gate is detected from GitHub's recorded runs (even if the edit is reverted later) and closed. Quote: "The gate never falls back to project CI when it cannot decide." (2026-10-15)
- F11 Optional future rule: switch to project CI after one maintainer approval, "but I wouldn't start with that complexity". (2026-09-19)
- F12 Monitor: GitHub API workflow state ("disabled_fork" vs "active") plus run conclusion for the SHA. "A few REST/GraphQL queries per PR, no build time." (2026-09-19)

### 1.8 Disclosure, PR template and agent-instruction rules

| ID | Condition | Action | Type | Status | Source | Quote |
|---|---|---|---|---|---|---|
| G1 | PR template Gen-AI checkbox "- [ ] Yes (please specify the tool below)" plus a commented "Generated-by: [Tool Name] following [the guidelines]" | Disclosure. Cannot be made mandatory (no .yml forms for PRs), so missing disclosure is "called out" | HUMAN / LLM (L3) | adopted (LC passed 2026-01-14) | Gen-AI guidelines in contrib docs, 2026-01-07; LC Gen AI Guidelines, 2026-01-09 / 01-14 | "we cannot make any fields "mandatory" here." |
| G2 | Agents opening PRs: `gh pr create --web` with the body prefilled and disclosure checked. The human must press submit | Adds "good friction". Increases the chance a human is behind the PR | DET (instruction) | implemented (PR #62575 AGENTS.md) | AGENTS.md overhaul, 2026-02-27; Update our AI coding policy..., 2026-06-08 | "with `--web` agent actually just opens browser tab and human has to actually press "submit"" |
| G3 | PR does not follow the process in the agent instructions | Filter out and close immediately, with a comment on why and how to fix | DET | proposed | AGENTS.md overhaul, 2026-02-27 | "the next step should also be to filter out and immediately close PRs that don't follow the correct process." |
| G4 | Agents must pause and ask the human to write the description. Checkbox "- [ ] I have reviewed this code myself" left for the human. Use `gh --web --template --draft` | PRs missing these steps are closed by an automated process, with a resubmit note. Applies equally to maintainer PRs | DET | proposed | Update our AI coding policy..., 2026-06-10 | "we should use an automated process to close PRs that miss these steps, with a note explaining how to resubmit them with human input." |
| G5 | Agent-drafted PR description must start with a human-typed "Human Summary". The agent text goes in a folded "AI Summary". Agents must not write or paraphrase it | Description format | DET (instruction) | LC broken. Reverted (#73660 reverted in #73669) 2026-09-24. Now in DISCUSS | LC / DISCUSS Human Summary, 2026-09-24 | "Agents are instructed to ask the user for the Human Summary and paste it verbatim - they must never write, suggest or paraphrase it" |
| G6 | Observation: after each new AGENTS.md expectation, agentic PRs complied within about a week, and the matching filter then "basically stopped working" | Prefer instructions before submission over filters after | HUMAN insight | observed | LC Limiting..., 2026-09-24 | "it took about a week, to get the agentic PRs to comply with what we asked them to do in our AGENTS.md" |
| G7 | Provider PRs touching live services (providers that have system tests): agents must run system tests and refuse to submit when they cannot (to go in `providers/AGENTS.md`). A prek hook requires system tests, with a shrinking exception list | No proof of system tests means no pass. Agentic review flags missing logs or screenshots. Close firmly when the contributor refuses to test | DET + LLM | proposed | Requiring real GCP testing evidence, 2026-10-07 | "We can add expectations for the agents to **always** do system test and to **refuse** to submit PR when the system tests cannot be run" |
| G8 | Maintainer comments "/run-system-tests" on a PR | System tests run. "Sticky": re-run on every new push | DET | proposed (pattern from the Magpie docs-preview action) | Requiring real GCP testing evidence, 2026-10-07 | "maintainer will be able to comment "/run-system-tests" on the PR - and the system tests will be run there." |
| G9 | PR "expected" to need system tests | "Waiting" status blocks merge. Workflow pauses on HITL until a steward approves "Go ahead". Can be sticky. Deterministic and AI "dangerous change" checks can override sticky. Maintainers review only when everything incl. system tests is green, and can override | DET + HUMAN | proposed (WIP AIP-124) | Requiring real GCP testing evidence, 2026-10-09 | "it will have a "Waiting" status that will block it from merging" |

### 1.9 Newsfragment, labels and other CI gates

| ID | Condition | Action | Type | Status | Source | Quote |
|---|---|---|---|---|---|---|
| N1 | PR has the label "skip newsfragment check" | `check-newsfragment-pr-number.yml` job skipped (re-evaluates on labeled/unlabeled events) | DET | implemented (PR #63983) | Do we still need the significant newsfragment check (#44378), 2026-03-20 | "require the "skip newsfragment check" label to be set. We can just follow what we do in 10 other similar cases." |
| N2 | Proposal: only `.significant` newsfragments, and only when users must act on migration | Towncrier/prek fails hard on any other type | DET | proposed | Newsfragments: when to add one, 2026-09-12 | "we should fail hard (configure towncrier in this way) to **only** allow .significant ones." |
| N3 | CI job duration above the recent trend on main | Automated warning (regression monitoring workflows, PR #68368) | DET | proposed / PR opened | Rebase your PRs for faster CI times, 2026-06-10 / 06-11 | "having such an automated warning on "main" builds would help us to catch things like this earlier." |

### 1.10 Issue assignment rules

| ID | Condition | Action | Type | Status | Source | Quote |
|---|---|---|---|---|---|---|
| I1 | Issue assigned to someone who is not a committer or collaborator | `breeze issues unassign` unassigns them | DET | implemented (PR #62585) | Active approach..., 2026-02-27 | "unassigns anyone who is not a committer or collaborator." |
| I2 | Assignment only to people the maintainer knows and trusts. The maintainer starts it. Nobody is assigned on request. "I'm working on it" comments are a self-decaying soft signal. Duplicate parallel PRs are fine | Policy (contributing docs PR #62417) | HUMAN, no automation | adopted (LC passed 2026-03-02) | Stop assigning unknown contributors, 2026-02-16; LC Change the way we approach assignments, 2026-02-24 / 03-02 | "I would not do any automation here - all that I described above is low-tech, no-automation" |
| I3 | Reintroducing assignment on request (or automating it) | Rejected: agents can request assignment instantly and block issues | n/a | rejected | Update our AI coding policy..., 2026-06-11 | "agents could effectively block issues indefinitely, making it much harder for real human contributors to find an opening." |

### 1.11 CODEOWNERS and area rules

| ID | Rule | Status | Source | Quote |
|---|---|---|---|---|
| O1 | Four levels: (1) no notifications (remove yourself), (2) observe only, (3) soft-commit to review "ready for review" PRs, pinged after a while, (4) gating "resident experts": at least one SHOULD review, can be bypassed when urgent, several per team | proposed | Clarifying the role of CODEOWNERS, 2026-04-26 | "at least **one** of those people should review the code." |
| O2 | Keep CODEOWNERS for level 4 (gating, optionally a protected-branch +1 from a CODEOWNER, plus coverage checks among active maintainers). CODENOTIFY for level 3 (can include non-committer stewards). Levels 1-2 are individual | proposed | Clarifying the role of CODEOWNERS, 2026-04-27 | "I lean toward reserving CODEOWNERS for Level 4 (hard commitment/gating)" |
| O3 | Three tiers: CODENOTIFY (notifications), CODEOWNERS (maintainer gating), CODESTEWARDS (stewards). Automated check for at least **two** people in CODESTEWARDS, auto-assigned as reviewers and CC'd on issues | proposed | Simplifying the new community provider acceptance process, 2026-04-28 | "If we automate checks for at least two people in CODESTEWARDS and ensure they are automatically assigned as reviewers" |
| O4 | CODEOWNERS cannot list non-collaborators | constraint | CODEOWNERS, 2026-04-26 | "CODEOWNERS has the property that it does not allow "non-collaborators" ... to be added to CODEOWNERS." |
| O5 | Different expectations per area (scheduler vs providers vs "common" providers). Every area needs maintainers who commit to quick review. Write it down as a "Social Contract" | proposed | What do we do with unreviewed PRs, 2026-06-30 | "For all defined areas, we **must** have maintainers who commit to reviewing those area PRs relatively quickly" |
| O6 | Area "stewards" tighten their own criteria and describe their area's complexity (feeds L4) | proposed | Concrete, complete idea for next steps, 2026-07-09 | "it would be up to the area "stewards" impacted to tighten the criteria they set" |
| O7 | Revamp CODEOWNERS to say whether listing means observing or committing to review, and to cover unassigned areas (providers and task-sdk have the least coverage) | proposed | PR auto-triage recent stats, 2026-05-19 | "We need to revamp CODEOWNERS to clarify whether listing implies observation or a commitment to review" |

### 1.12 Labels used (all mentioned by him)

- `ready for maintainer review` (D10-D13, F1-F5)
- `closed because of suspicious changes` (A2, proposed)
- `closed because of multiple quality violations` (A1, proposed)
- `closed because of open PR limit` (A5, implemented)
- `skip newsfragment check` (N1, implemented)
- `use fork ci`, `use project ci` (F9, proposed)
- `area:*` (S4, filtering)
- GitHub Draft state, used as the "not ready, author's turn" state, and the GitHub "Ready for review" button

---

## 2. End-to-end triage workflow, as he describes it

States and owners (sources: Make our "ready for review"... 2026-03-03/04; LC Implement auto-triage 2026-03-06; Current auto-triage stats 2026-04-03; Maintainer PR dashboard 2026-05-06; PR auto-triage recent stats 2026-05-19; Update our AI coding policy 2026-06-12; AIP-120 threads 2026-09/10):

1. **Before the PR (contributor + agent)**. AGENTS.md tells agents what good PRs look like: `gh pr create --web` with disclosure prefilled, the human presses submit (2026-02-27, 2026-06-08). Open-PR cap of 5 for users without write access (2026-09-24). Planned: human-typed summary, self-review checkbox, `--draft` (2026-06-10, 2026-09-24). Planned: CI runs in the contributor's fork (AIP-120).
2. **Opened, untriaged (non-collaborator)**. Picked up in the next triage run (manual, by the triager, about daily or every two days). Collaborators skip this. They self-label "ready for maintainer review" (2026-03-03).
3. **Workflow approval (first-time contributor)**. The triager sees the diff in the terminal and confirms with "y". If changes look suspicious, the proposal is to close all of the author's PRs with the "closed because of suspicious changes" label (2026-03-02/04).
4. **Deterministic checks**: failing CI by category, static checks, commits behind main, conflicts, missing CI, unresolved review threads, the author's count of failing open PRs. More than 90% (later "about 80%" and "60-80%") of problematic PRs are settled here (2026-03-02, 2026-06-10, 2026-09-10).
5. **LLM check** (only if step 4 passes): PR guidelines, description vs code, disclosure plausibility (2026-03-04, 2026-03-11).
6. **Proposal review by the human triager**. The tool proposes actions in bulk. The triager accepts, edits or rejects each one. Nothing is posted without that decision (2026-02-27 item f, 2026-03-12).
7. **Outcomes**:
   - **Draft** (author's turn). Status note folded into the PR description (earlier: a comment). Author assigned. Footer "Drafted-by ... reviewed by @maintainer before posting". The author fixes the issues and clicks "Ready for review", then is re-triaged (2026-03-06, 2026-05-22, 2026-06-12).
   - **Rebase / rerun** when failures are stale-main pollution or flaky (2026-05-06, 2026-06-12).
   - **Close**: author has more than 3 failing open PRs, suspicious changes, PR limit closure, or stale (T1/T2).
   - **ready for maintainer review** label (no message posted). Author unassigned (2026-06-10, 2026-06-12).
8. **Waiting for author**. Timers: more than 2 weeks with no response led to closure (observed 2026-04-03). Later a 7-day no-response window, then stale-sweep close (2026-05-06). AIP-120: 7-day inactivity, then close, reopen restarts the clock.
9. **Ready queue**. Triage leaves it alone unless it starts failing. Then the label is removed, except when a merit discussion has started (2026-05-19). Human maintainers review, ideally per area with `/maintainer-review` (or `/pr-management-code-review`) filtered by `area:*` (2026-05-06, 2026-05-19). The first question should be "Why?" (2026-06-18).
10. **Merge**. Maintainers may apply small mechanical fixups themselves and merge on green (2026-10-06).

Who acts:
- **Triager**: Jarek alone at first, then Yeonguk Choo and André Ahlert helping. Plan: a rotation of volunteer maintainers (2026-03-02, 2026-03-12, 2026-05-11).
- **Author**: fixes the issues, un-drafts, prioritises within the limit of 5.
- **Area maintainers / stewards**: review the ready queue. Stewards approve system-test runs (proposed).
- **Bots / CI**: only deterministic parts (AIP-120 reconciler, label skips, cap). He wants to move proven deterministic rules into CI (2026-06-09 "third way", 2026-07-09, 2026-10-05).

Evolution of the tooling:
- `breeze pr auto-triage` (Python CLI, GraphQL, PR #62682, LC 2026-03-10)
- then an agentic SKILL-only version in plain English (PR #65648, 2026-04-22), with rules in `.github/skills/pr-triage/classify-and-act.md` (2026-05-06)
- then moved to airflow-steward, later Apache Magpie (PR #66677, 2026-05-11)
- then a proposed "pull" model with Airflow DAGs as the engine (2026-09-10)
- and finally deterministic CI or repo configuration for anything community-wide (2026-10-05).

---

## 3. Numbers and statistics he shared

| Date | Context (thread) | Number(s) |
|---|---|---|
| 2026-01-06 | Gen-AI guidelines | 25 PRs closed from one contributor (Arunodoy18) |
| 2026-03-02 | Active approach to AI slop | more than 90% of problematic PRs can be assessed without LLMs; hundreds of PRs in minutes |
| 2026-03-04 | "ready for review" stricter | about 90% of the filter is deterministic; pre-commit has grown to over 170 checks |
| 2026-03-11 | LC auto-triage (first run) | about 100 PRs triaged in under 1 hour; 30 converted to Draft; "a few" closed; 0 marked ready; LLM 20 s to 2 min per PR; 15% or fewer of the batch LLM-assessed |
| 2026-03-12 | LC auto-triage | 200 already draft (at least 100 converted by him); 30-40 "ready for maintainer review"; at least 10 authors thanked him; claim: "at the very least two orders of magnitude more PR traffic" can be handled |
| 2026-03-11 | Meeting Notes teaser | example: PR claims "22 test cases", diff adds 7 lines; branch 648 commits behind main |
| 2026-04-03 | Current auto-triage stats | all 500+ open PRs triaged in about 4 h; open PRs 540 to 492; about 1 min per PR; about 20 earlier smaller sessions; about 80% of PRs from external contributors; of 102 PRs in a "final" state: 40 merged (about 40%), 62 closed without response (about 60%); 127 PRs ready for the label; open: 492 total, 400 contributor, 126 ready, 200 drafted/triaged (128 waiting for a contributor response) |
| 2026-04-22 | Current auto-triage stats | about 450 open PRs after the SKILL rewrite |
| 2026-05-03 | Improving security handling | PR #66319: -7,500 lines of skills, +1,400 lines of adoption bootstrap |
| 2026-05-06 | Maintainer PR dashboard | 459 open non-bot PRs; 342 from contributors; 187 ready (54% of the contributor queue); about 98 contributor non-draft PRs untriaged; 1 older than 4 weeks; backlog -56 PRs over 6 weeks; dashboard built in about 1 h |
| 2026-05-11 | Improving security handling | first week-over-week drop in PRs opened; 187 ready |
| 2026-05-19 | PR auto-triage recent stats | about 50% author response rate; opened/closed about 40 per day; 154 ready, over half with no maintainer comment; providers and task-sdk least covered |
| 2026-05-21 | Hand off airflow-steward to Magpie | Apache HTTP Server received 60 security reports in one day (normally a few a month); security handling "order of magnitude" faster |
| 2026-06-10 | What do we do with unreviewed PRs (4 weeks) | 373 open PRs from non-maintainers; 95 drafts (70% drafted during triage); 211 ready; about 108 ready with no comment; 23 approved awaiting merge; about 80% of triage actions deterministic, about 100 items could be cleared by CI scripts |
| 2026-06-11 | Unreviewed PRs | ready-queue trend: Apr 3: 127, May 19: 154, Jun 10: 211 |
| 2026-06-12 | Update our AI coding policy | 66 actions out of 500 PRs: mark-ready 21, workflow-approvals 20, reruns 3, violation folds 7, request-author-confirmation 4, pings 4, stale-draft closes 5 |
| 2026-06-22 | CI compute analysis (2 weeks) | selective checks: about 60% fewer compute-hours than full tests, about 81% fewer than canary; PRs run 19-40% of "everything"; tracked provider_dependencies.json wasted about 2,700 compute-hours per 2 weeks (about 12%); canary AMD 4 to 2/day, ARM 8 to 2/day saves about 6% AMD, detection up to about 12 h instead of about 6 h; combined about 20% AMD; about 3,960 job-hours waiting for runners; big runs wait about 2.5x longer; dedicated hardware about 4x; GitHub about 3x runners in 2020; GitHub scaling 20-30x; accidental full builds 3.3% of runs, 3.6% of compute; delaying heavy jobs could save about 5%; about 12% of time spent on PRs cancelled by a new push |
| 2026-07-09 | Concrete idea for next steps | about 60 PRs handled quietly over recent weeks; over 230 open test cases for calibration |
| 2026-09-10 | "scheduled" triaging | security: more than 60 issues/month (10x vs 6 months earlier); 60-80% of triage actions deterministic |
| 2026-09-18 | Run CI in forks (AIP-120), 90 days | 4,009 PRs (about 45/day); 65% external; external close-without-merge 19.5% vs 5.2% for committers/collaborators (about 3.8x); 508 external PRs (about 5.6/day) wasted; full matrix 4,392 job-minutes / 308 jobs vs docs-only 31 job-minutes ("60x", corrected to about 142x on 2026-09-19); free accounts: 20 concurrent jobs; docs about 8 min either way; large core about 88 min in a fork vs about 35 min on project CI |
| 2026-09-18 | AIP-120 reply | "less then 7%" (stat in the AIP; the question it answers is not visible in the corpus) |
| 2026-09-19 | AIP-120 | Airflow stays within about 6 full-time runners |
| 2026-09-21 | LC open-PR limit | over 806 PRs from 369 human non-committer authors: cap 5 closes 180 PRs (22.3%), 24 authors; cap 10 closes 100 (12.4%), 9 authors; cap 20 closes 39 (4.8%), 4 authors; "100 open PRs" (old) vs "1,000 open PRs" (now); top-author table (for example 35 / 34 / 27 open PRs); potiuk: 21 open, 20,710 others' threads, 14,692 reviews, 6,092 authored, 5,589 merged (92%); buckets A 9 people / 103 open, B 6 / 115, C 9 / 82 (about 21% merge, about 4 reviews per landed PR) |
| 2026-09-21 | LC open-PR limit | example contributor: 43 weeks, 90 authored, 81 merged, 3 closed, 6 open, average 6.4 open, peak 15 |
| 2026-09-22 | Run CI in forks | 10 large core PRs iterate up to about 15x slower in forks; about 36 h for 10 rebased large PRs; about 10 people actively contributing to the dev env |
| 2026-09-24 | RESULT PR limit | dry run: 20 contributors over the limit, about 210 PRs closed, about 70 kept open (maintainer engaged) |
| 2026-09-25 | RESULT PR limit | 210 PRs closed from 21 contributors; largest 31, 26, 24, 21; most 13 or fewer (typically 5-10); 110 opened in September, 63 in August |
| 2026-10-05 | Next step after the PR limit (9 days) | bulk PRs (contributors opening more than 5/week) about 63/week before, about 42/week Jul-Aug, 17 in the first week after; distinct contributors 74 vs 82/week before and 76 Jul-Aug; contributor PRs/day about 22% lower than the previous 4 weeks; open contributor queue about 814 to about 590 (grew from about 260 in Jan to about 870 in early Sep); drafts share 4% to 7%; fork vs ASF runner estimates: UI translations 33 jobs / 132 job-min, about 49 vs 49 min; single provider 50 / 300, about 31 vs 34 min; core fix 37 / 275, about 35 vs 35 min; full matrix 138 / 1,307, about 49 vs 85+ min; canary 309 jobs about 2 h vs about 4 h; large PRs 1.5-2x slower |
| 2026-10-06 | Merging "almost" ready PRs | more than 50 fixup-merges with no push-back |
| 2026-10-08 | Next step after the PR limit | open PRs "hovered around 800" (corrected on 2026-10-15); external PRs per week Jul 13 to Oct 8: 127, 171, 184, 144, 142, 138, 126, 185, 197, 132, 145, 118, 76 (partial); committer PRs per week: 100, 113, 162, 114, 85, 95, 81, 37, 181, 113, 161, 185, 120 |
| 2026-10-09 | Merging "almost" ready PRs | 2026-05-09 to 10-09: 4,839 merged, 3,673 after cleanup, 74 committers; with maintainer fixup 120 (3.3%), suggestion-only 66 (1.8%); contributor PRs 1,796: 79 (4.4%); committer PRs 1,877: 41 (2.2%); contributor fixup rate May 2.3%, Jun 3.8%, Jul 2.5%, Aug 3.7%, Sep 5.8%, Oct 12.6% (16 of 127); 225 fixup commits, median 1; 72.5% (87/120) merged by the fixer; potiuk 37 PRs / 47 commits (about 30%); non-author non-committers commit to someone else's PR in 1.6% of PRs |
| 2026-10-09 | Merging "almost" ready PRs | 5 significant PRs rebased together: about 15 h in forks vs about 2 h on ASF runners |
| 2026-10-15 | Next step after the PR limit (19 days) | bulk PRs 17 and 27 in the two weeks after vs 63/week before; 74 and 82 distinct contributors/week; contributor PRs/day 21.6 vs 21.9 (Jul-Aug); correction: queue went to about 590 (not about 800), flat until Oct 7, then 655 on Oct 14 |

---

## 4. Tools, commands, PRs and AIPs he references

Breeze / CLI:
- `breeze pr auto-triage` (PR #62682): maintainer-driven batch triage of non-collaborator PRs. Deterministic plus LLM (Claude Code, Codex, Copilot). GraphQL. CLI and TUI modes. Remembers the chosen model. Follow-ups: #63300 (unresolved-review signal, filters), #63315/#63317/#63318/#63319/#63320 (improvements), #63422 (LLM sandbox against prompt injection), #64669 (big update incl. `--reviews-for-me`).
- `breeze pr stats` (PR #64667): triage statistics.
- `breeze issues unassign` (PR #62585): unassigns non-committers/non-collaborators.
- `breeze ci upgrade`: earlier maintainer tool, cited as the model.
- `breeze ci audit` / `breeze ci reconcile` (AIP-120, PRs #73378, #74823): contributor fork setup check / CI-side relabel and comment.
- `breeze release management prepare-providers-release`: to be replaced by a "Provider doc preparation skill" (2026-04-23).
- `gh workflow enable ci-amd.yml -R <user>/airflow`: the one-time contributor step for AIP-120.
- `prek run --from-ref main [--stage pre-commit]`: the static-check reproduction command quoted in triage comments.
- `dev/close_prs_over_open_pr_limit.py`: one-time PR-limit closure.

Skills (Airflow `.github/skills/`, then airflow-steward, then Apache Magpie):
- PR triage skill, SKILL-only rewrite (PR #65648). Rules in `.github/skills/pr-triage/classify-and-act.md`.
- `/maintainer-review` (PR #65981): maintainer-driven review. AI comments are reviewed before posting.
- `/pr-management-code-review`, `/setup-steward` (later `/magpie-setup`), `setup-steward upgrade | verify | override SKILL`, `/setup-apache-steward`.
- Dashboard skill (Airflow PR #66464, steward PR #68): PR health dashboard with deterministic "What needs attention" cards.
- Comment attribution formats (PR #65965). Fix: do not remove the ready label on merit discussion (steward PR #232). Do not list failed jobs (PR #67322).
- Security skills (airflow-steward PRs #1-#5): allocate-cve, import-security-issue, import-security-issue-from-pr, invalidate-security-issue, sync-security-issue, deduplicate-security-issue. Tools generate-cve-json (100 tests) and oauth-draft (22 tests). CI with prek, zizmor, CodeQL, the ASF allowlist check, dependabot (7-day cooldown).
- Release-management `verify-rc` skill with a "test your own changes" override (PR #73697).
- Magpie mini-eval framework. AGENTS.md split plus evals (GSoC). Skill optimizer that turns skills into deterministic Python (magpie issue #1342). `sandbox-test` skill.
- Install: `claude plugin marketplace add apache/magpie` and `claude plugin install magpie-setup@apache-magpie` (2026-09-22).

Repos / projects:
- apache/airflow-steward, renamed Apache Magpie (TLP established about June 2026, repo apache/magpie, magpie.apache.org). LC handoff passed 2026-05-21. Adoption PRs #66283 (submodule, closed), #66319, #66677. Overrides modelled on kustomize.
- airflow-s/airflow-s (private security tracker). Stays with the Airflow PMC.
- Proposed `airflow-agentic-workflows` repo with Airflow DAGs as the triage engine. WIP AIP-124 "Airflow-Powered Agentic Workflows for Project Operations".

AIPs:
- AIP-118 self-hosted runners (RunsOn evaluation).
- AIP-119 AWS account anomaly monitoring and guardrails.
- AIP-120 CI in contributor forks.
- AIP-124 (WIP) Airflow-powered agentic workflows.
- AIP-85 (steward tooling, system tests self-service).
- AIP-95 (provider governance and steward metrics).

Governance / docs PRs:
- #60158 (Gen-AI guidelines, merged 2026-01-09), #60531 (template tweak), #62417 (assignment policy), #62575 (AGENTS.md PR creation), #63983 (skip newsfragment label), #67557 (generalised escalation path), #67790 (example of the new status note), #68368 (CI regression monitoring), #68533/#68801/#68802/#68814/#68821 (CI compute).
- #73469 (open-PR limit and `contributing-docs/32_open_pull_request_limit.rst`), #73754 (temporary cap removal), #73660 / #73669 (Human Summary, reverted).
- #73378, #74819, #74820, #74822, #74823, #74825 (AIP-120).
- Infra: infrastructure-asfyaml #111 (creation_cap), #127 (test workflow), #133 (test skill), #135 (bypass list); INFRA-28454 (deployment); infrastructure-actions #1332 (docs preview).

External references:
- GitHub "pull request creation cap" (community/maintainers discussion #840)
- GitHub agentic workflows
- Hugo van Kemenade's `gh-triage`
- Apache Spark PR template (#42469)
- Pandas #62561 and scikit-learn #31568 (both dropped assignments)
- Apache SeaTunnel (fork CI)
- OpenAI Decisions API
- ASF llm.apache.org

---

## 5. Problems he says remain, and what to automate next

Problems:
- **Review backlog grows while triage works.** Ready PRs: 127 (Apr 3), 154 (May 19), 187 (May 6/11), 211 (Jun 10). About 108 of them had no comment. "I do not think we have a problem with "marking PRs ready," but with handling those that are ready." (What do we do with unreviewed PRs, 2026-06-10)
- **Flaky tests and broken main** force manual assessment (Current auto-triage stats, 2026-04-03). Unrelated failures need a rebase signal (2026-03-03).
- **LLM mistakes**: "a few were significantly wrong and I would not post them" (LC auto-triage, 2026-03-11). He calls triage "unfair" in some cases (2026-04-03).
- **False negatives**: the ready label was removed while the real blocker was maintainer alignment. Fixed by steward PR #232 (2026-05-19).
- **Notification noise** from triage comments led to de-noising and folding into the PR body (2026-06-10/12). Comments were too long, so failed-job lists were dropped (2026-05-22).
- **Single point of failure**: triage is a "push" model run by him in the Claude CLI. "This creates a Single Point of Failure (SPOF)." ("scheduled" triaging, 2026-09-10)
- **Providers and task-sdk** lack reviewer coverage. CODEOWNERS semantics are unclear (2026-05-19).
- **Token cost and abuse**: per-PR AI checks in CI can't be afforded because submitters could burn tokens. Copilot billing is unclear (2026-04-28, 2026-10-07).
- **GitHub controls are weak**: drafts are not counted by the PR cap. No workflow-wide concurrency cap. Shared ASF runner pool ("tragedy of the commons") (2026-09-24, 2026-10-09).
- **PR cap not enforced** until INFRA-28454 is fixed (2026-09-26). The open queue started growing again after Oct 7, to 655 (2026-10-15).
- **AGENTS.md** is too long. Agents miss instructions. Some instructions contradict each other (newsfragments) (2026-06-11, 2026-09-12, 2026-09-24).

Automate next (his stated sequence):
- Convert proven deterministic triage steps (about 80%) into CI scripts with no AI at runtime: "we use AI to convert the SKILLS we have into deterministic CI code that does those triage steps (no AI used at all at runtime)" (Update our AI coding policy, 2026-06-09). Also: about 100 items could be cleared by CI scripts (2026-06-10). Magpie "Autonomous mode when we prove it works with supervision" (2026-07-09).
- Roadmap: "PR limit → CI in forks → Self hoste runners → Airflow-Backed Maintainer AI Assistance" (REMINDER dev call, 2026-09-23). Evaluate each step for about a week (2026-09-22): 1) PR limit, 2) fork CI (cheap, deterministic), 3) per-area rules in Magpie triage, 4) Airflow in the cloud running scheduled triage (heavy lift).
- Pull/inbox model: Airflow DAGs run deterministic actions in the background and send Slack or inbox notifications to per-area and security inboxes for bulk HITL decisions (2026-09-10/11).
- Auto-triage of issues (Groovy issue-triage skills from Paul King) and LLM classification for provider releases (2026-03-02, 2026-05-18).
- Per-area expectations, PR size limits, per-area PR caps, maintainer-time assessment (2026-06-30, 2026-07-09).
- System-test gating with steward HITL and `/run-system-tests` (2026-10-07/09).
- Agent-assisted "quick merges" for easy PRs, dependabot and backports. Review 20-30 easy PRs per hour with agent-applied nits and fixups (2026-05-11, 2026-09-22).
- Faster, more accurate PR classification and maintainer UX (2026-05-11). Dashboard extensions (2026-05-06).
- A validation step in CI for first-time contributions, inside selective-checks / build info (2026-06-09/10).
- A newsfragment type hard-fail and a system-test prek hook (2026-09-12, 2026-10-07).

---

## 6. What should NOT be automated or must stay human

- **Never post on the project's behalf without human review**: "it should never, ever publish anything on our behalf that a human user did not review." (Automated review tools, 2026-02-17)
- **Deterministic is fine, unsupervised non-deterministic is not**: "When it's deterministic - fine, but non-deterministic answers produced without human oversight, for me at least, is a non-go." (Automated review tools, 2026-02-17)
- **Not run automatically; the maintainer triggers every action**: "The most important thing here is that it does NOT run automatically." (Active approach..., 2026-03-02). Also "A maintainer running the triage must trigger all those actions."
- **Bulk proposals, human decides**: "we won't do those things fully automatically - but we will get reviewable action proposal in bulk" (Active approach, 2026-02-27).
- **Human comments carry more weight than bot comments**: "comments and actions coming from a human maintainer have more value than those from a bot" (Active approach, 2026-03-02).
- **Sensitive parts keep a human in the loop** even if high-confidence parts are automated later (2026-03-02). The LLM portion "is a bit more nuanced and we can decide after we try" (2026-03-03).
- **Workflow approval for new contributors**: the human looks at the diff and confirms (2026-03-04).
- **Review is human-to-human community building**, not bug finding: "review is not about finding bugs in the code ... it's all about human-human interaction." (Automated review tools, 2026-02-17)
- **The real review stays human and starts with "Why?"**: "their first question should be, "Why?"" (Let's not lose the high bar, 2026-06-18). The top of the queue "needs us, the humans" (2026-05-06).
- **Assignment decisions**: no automation, judgment by the maintainer. He rejects auto-unassign timers and mechanical assignment on request (2026-02-16/19, 2026-06-11).
- **Edge cases between parallel PRs**: case by case, by humans, using consensus (Stop assigning..., 2026-02-16).
- **Concerns about fully automated Copilot reviews**: they "may discourage contributors who expect human interaction" (Triage + review AI assistance, 2026-04-28).
- **Maintainers stay accountable** for AI comments they accept: "keep maintainers ultimately responsible for comments they accept to send." (2026-04-28)
- **Security-sensitive parts**: system-test runs need steward HITL approval (proposed). Maintainers can override the gate (2026-10-09).
- **Mass-closing the backlog** for AIP-120 is "a separable proposal" with risks that need care and messaging (2026-09-18).
- **The ready-queue review commitment** is a social contract among maintainers, not tooling: "No single person or automated tool can achieve this without others contributing their time" (Unreviewed PRs, 2026-06-11).
- **Long-term vision**: triage could become 90% automated, "leaving only difficult cases for humans" (Current auto-triage stats, 2026-04-22). Transactional fixes could be automated so humans focus on community contributors (Human Summary DISCUSS, 2026-09-27).

---

## Notes on inconsistencies found

- **Drafts and the PR cap**: on 2026-09-21 he wrote that the GitHub policy "also applies to Drafts" since GitHub changed it. The RESULT on 2026-09-24 says drafts "do not count towards the limit yet - GitHub does not support counting them". On 2026-10-05 he again says drafts do not count.
- **Open queue after the closure**: about 800 (2026-10-08), corrected to about 590 (2026-10-15).
- **Full-matrix vs docs ratio**: "60x" (2026-09-18), corrected to about 142x (2026-09-19).
- **LC deadline for the PR limit**: first said Thursday 24th 12:00 CEST, corrected to 19:00 CEST (2026-09-21).
- **Share of deterministic triage actions**: more than 90% (Mar), about 90% (Mar), about 80% (Jun), 60-80% (Sep).
- **No-response timers**: more than 2 weeks observed in April, then a 7-day window in May, then 7 days in the AIP-120 design.
- **A1 threshold** ("more than 3 open PRs failing quality checks") appears only in the 2026-03-02 proposal. No later message confirms it ran as written.
