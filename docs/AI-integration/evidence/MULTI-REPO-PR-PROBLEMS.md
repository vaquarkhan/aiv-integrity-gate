# Recurring problems with incoming PRs across six projects

Corpus: `aiv-airflow-bench\corpus\benchmarks\{cosmic,curl,flink,ghostty,godot,kafka}`, 879 PR records, window 2025-10-22 to 2026-10-22. Scripts: `%TEMP%\prfeat.py`, `agg2.py`, `final.py`, `comp.py`, `cf.py`, `prec.py` (read-only over the corpus). All merge rates are merged / (merged + closed unmerged); open PRs are excluded. Bots (renovate, dependabot, weblate, ghostty-vouch) are excluded from every rate.

## 0. How the sample was drawn (read this first)

From `collect_slop_benches.py`, `collect_hit_benches.py`, `repair_slop_benches.py` and the manifests:

- Per quarter, GitHub search for the latest hits of: `"Generated-by:"` (cap 8), slop/hallucination/vibe-coding language (cap 8), `"<<<<<<< SEARCH"` markers (cap 4), merged controls (cap 6, latest merged PRs without disclosure or slop words). Godot, Ghostty and COSMIC also got 6 latest closed-unmerged PRs per quarter with no AI signal. A later repair pass added `"AI slop"`, `hallucinated`, `"vibe coding"` hits.
- `prs\*.json` is a superset of `cases.json`: every PR fetched while paging search results is stored, including ones later rejected from the case list (curl 232 of 249 files are not cases). I analysed all stored PRs and kept the cohort label for reference.
- PRs with more than 5,000 additions or 40 files are stored without file patches (`too_big`, 36 PRs), so file-level checks miss the largest PRs.
- Consequences: AI-flagged PRs are heavily over-sampled; merged PRs are over-sampled through the control cohort; "latest N per quarter" favours late-quarter PRs; only PR text and patches are stored (no review comments, labels, CI results, author association or commit messages).
- curl lands most PRs by pushing commits and then closing the PR, so `merged=false` there does not mean rejected (core maintainers vszakats and bagder have 131 PRs, 4 marked merged). curl merge rates are reported for completeness and are not used as evidence.
- "Newcomer" below means the author has no other merged PR in this sample. It is a proxy for "first-time contributor", not the real GitHub author association.

AI flag definitions (template boilerplate stripped first, e.g. COSMIC's "If you are using an LLM..." and Flink's "Low-effort AI-generated PRs will be closed"):
- `ai_strong`: filled `Generated-by:`, AI checkbox ticked "Yes", Claude Code footer, `Co-Authored-By:` an AI, an "AI disclosure:" line that is not a denial, "used/generated with <tool>", or an agent signature (ClawOSS).
- `ai_any`: `ai_strong` or a tool name (Claude, Codex, Copilot, Cursor, Gemini, Cline, opencode, LLM...) in title or body, minus explicit denials ("No AI used").

## 1. Per project

### COSMIC (pop-os/cosmic-comp)
- Sample: 74 PRs (10 weblate bots). Non-bot: 48 closed, 15 merged, 1 open. Population: 425 PRs in the window. Bias: 22 closed-unmerged random sample + 18 merged controls + search hits; COSMIC banned LLM-written code on 30 Sep 2026.
- AI share: 20 of 64 non-bot (31%), 13 strong. Merge rate AI 3/20 (15%) vs non-AI 12/43 (28%). Newcomers only: AI 3/20 (15%) vs non-AI 4/30 (13%). The AI gap is explained by author history here.
- Size: merged median churn 10 lines (1 file); closed median 93 (3 files). AI closed median 125 lines, AI merged median 8.

| Pattern | n | closed | merge rate present vs absent | closed examples | merged examples |
|---|---|---|---|---|---|
| Newcomer author | 51 | 43/48 | 14% vs 62% | 1932, 1934, 1969 | 1986, 2572, 2809 |
| Claude Code layout (`## Summary` + `## Test plan`) | 9 | 9 | 0/9 vs 28% | 2009, 2116, 2134 | none |
| Newcomer + AI + no tests for 20+ new source lines | 9 | 9 | 0/9 | 2009, 2144, 2161 | none |
| Conventional-commit title (project does not use them) | 27 | 22 | 15% vs 30% | 1932, 1934, 2116 | 1998, 1999, 2826 |
| Description much longer than diff (150+ own words, >3 words per changed line) | 14 | 12 | 14% vs 27% | 2116, 2197, 2205 | 2809, 2813 |

Also: mandatory attestation box left unticked 0/4 (2260, 2541, 2621); newcomer change of 500+ lines 0/4 (1969, 2211, 2555); closed within 1 hour 5.

Qualitative (17 closed AI, 3 merged AI read): closed AI PRs are multi-file features nobody asked for (nearest-neighbour zoom filter 2161, D-Bus idle-inhibitor export 2552, "approval mode" 2920), Claude Code footer and Summary/Test-plan layout, one author filed three in a week (modelmiser 2161/2163/2172). Several authors say they cannot evaluate the code (2621: "im not a rust dev, nor know much about compositors"). The 3 merged AI PRs are 6 to 12 line single-file fixes where the author explains in their own words how narrowly the tool was used (2826: codex "for faster search of concrete code subset").

### curl
- Sample: 249 PRs (39 renovate). Non-bot: 179 closed, 16 merged, 15 open. Merge flag unreliable (see section 0). Bias: only 18 cases; the rest are pages of search results.
- AI share: 8 of 210 (4%), 2 strong. 4 of the 8 are core maintainers crediting AI bug finders ("Pointed out by Codex Security" 20955, "Found by Codex Security" 20989, Copilot in 22009). Non-core AI PRs: 20003, 23472, 23482, 23526 (23526: "found by an LLM-Enhanced Static Analysis Engine and verified manually").
- Patterns (prevalence only): closed within 1 hour 24 (mostly maintainers superseding their own PRs, e.g. 19513, 19786); CI/workflow edits 29 (all maintainer GHA work); newcomer change of 500+ lines 15, none marked merged (19504, 19817, 19827); no description 23 (19418, 19422, 19518); same issue touched by two authors within 30 days 4 (20040, 20046, 20113). Merged examples: 19469, 19995, 21858.
- Qualitative: no agent-style PR bodies in this sample. curl's AI problem was the bug bounty inbox (manifest), which is not in PR data.

### Apache Flink
- Sample: 131 PRs (3 bots). Non-bot: 93 merged, 11 closed, 24 open (base 89%). Bias: 20 Generated-by hits + 24 merged controls + 7 slop-search hits. Closed PRs are rare, so pattern rates rest on 11 closed PRs.
- AI share: 52 of 128 (41%), 50 strong (Generated-by or ticked checkbox). Merge rate AI 33/40 (82%) vs non-AI 60/64 (94%). Newcomers: AI 9/12 (75%) vs non-AI 9/11 (82%).
- Size: merged median churn 124 (4 files), closed 335. AI merged median 338.

| Pattern | n | closed | merge rate present vs absent | closed examples | merged examples |
|---|---|---|---|---|---|
| Churn 1,000+ lines or too big for patch | 17 | 3 | 81% vs 91% | 27997, 28000, 28797 | 27137, 27154, 27216 |
| Newcomer change of 500+ lines | 8 | 2 | 4/6 (67%) vs established 18/20 (90%) | 27840, 28797 | 27137, 27235, 27407 |
| Lockfile mass regeneration (npm audit, 13,748 and 18,113 lines) | 2 | 2 | 0/2 | 27997, 28000 | none |
| Closed only after long inactivity (closed AI median 1,683 h open; 4 of 7 over 30 days) | 7 | 7 | n/a | 27840, 28000, 28779 | n/a |
| Template placeholders left (`(yes / no)`, `*(For example:`) | 14 | 0 | 12/12 merged | none | 27589, 27974, 27978 |

The last row is a non-problem: Flink committers merge PRs with template residue every time.

Qualitative (7 closed AI, 16 merged AI read): disclosure is routine and mostly from committers (MartijnVisser 7, jnh5y 3, raminqaf 3) with a JIRA key, tests and the standard template. Closed AI PRs are mass dependency regeneration (27997, 28000), a 2,122-line new PyFlink DataFrame API with 15 new files (28797), and 27840 ("The most of codes/descriptions are made by claude"). They die of review starvation, not fast rejection.

### Ghostty
- Sample: 130 PRs (9 bots). Non-bot: 64 merged, 43 closed, 14 open (base 60%). Population 2,492 PRs, 447 closed unmerged. Bias: 24 random closed + 24 merged controls + search hits. Policy since 22 Jan 2026 (#10412): AI only for accepted issues and maintainers.
- AI share: 58 of 121 (48%), 49 strong. Merge rate AI 26/47 (55%) vs non-AI 38/60 (63%). Newcomers: AI 9/23 (39%) vs non-AI 13/32 (41%). Maintainers disclose AI too (mitchellh 8, bo2themax 9).

| Pattern | n | closed | merge rate present vs absent | closed examples | merged examples |
|---|---|---|---|---|---|
| Closed within 1 hour of opening | 26 | 26/43 | 0% vs 79% | 10515, 12167, 13097 | none |
| Newcomer + 500+ lines + no linked issue | 10 | 8 | 1/9 (11%) vs established 500+ 7/9 (78%) | 10316, 12369, 13372 | 11641 |
| Churn 1,000+ lines | 18 | 10 | 29% vs 65% | 10316, 12167, 13304 | 10455, 11641, 14801 |
| Conventional-commit title | 18 | 9 | 44% vs 63% | 10322, 10361, 10515 | 10384, 10396, 11810 |
| Wrong target or fork sync ("pushed to wrong repo", "Sync/upstream", "Sample PR 4") | 5 | 5 | 0/5 | 10316, 12333, 12372 | none |

Of the 26 fast closes, 22 are newcomers, 13 AI-flagged, 15 have 300+ lines. Also: new file of 500+ lines 1/4; agent planning notes committed (9919 adds `notes/claude_unfocused_window_plan.md`); the only patch-marker hit in the corpus (13408, elision comment in a fork-sync PR).

Qualitative (21 closed AI, 16 merged AI read): merged AI PRs come from maintainers and vouched regulars, fix a referenced issue, and state human review (13226 mitchellh: "I've proofread (and mostly modified) every line"). Closed AI PRs are drive-by features without an accepted issue (SSH image paste 10515, Win32 runtime 12167, configurable links 13097: "briefly human reviewed though I'm pretty unfamiliar with this codebase"), often closed within minutes.

### Godot
- Sample: 191 PRs, no bots. 63 merged, 72 closed, 56 open (base 47%). Population 7,589 PRs, 1,094 closed unmerged. Bias: 24 random closed + 24 merged controls + slop/tool hits. Policy 30 Jun 2026: vibe-coded PRs are an auto-ban; new contributors need permission for features.
- AI share: 40 of 191 (21%), 28 strong. Merge rate AI 3/28 (11%) vs non-AI 60/107 (56%). Newcomers: AI 3/27 (11%) vs non-AI 22/64 (34%). Largest AI penalty that survives the newcomer control.

| Pattern | n | closed | merge rate present vs absent | closed examples | merged examples |
|---|---|---|---|---|---|
| Newcomer change of 500+ lines | 22 | 12 | 2/14 (14%) vs established 6/7 (86%); with no linked issue 0/7 | 113175, 115149, 118125 | 114075, 114917 |
| Closed within 1 hour | 24 | 24/72 | 0% vs 57% | 115149 (6,333 lines), 118801 "Voxel engine", 120629 | none |
| Description much longer than diff | 49 | 26 | 32% vs 53% | 112256, 115264, 115688 | 118554, 118777, 119834 |
| No tests for 20+ new source lines | 81 | 28 | 38% vs 51% | 113175, 114651, 114884 | 114813, 114917, 114921 |
| Newcomer + AI + no tests | 16 | 9 | 1/10 | 114884, 118816, 118823 | 117913 |

All 24 fast closes are newcomers. Also: agent files 0/2 (113175 adds `IMPLEMENTATION.md`; 118681 a defensive `CLAUDE.md`); competing PRs for one issue on one day (124762 closed, 124757 merged).

Qualitative (25 closed AI, 3 merged AI read): many closed AI PRs are small and plausible (median 46 lines, 1 file) with long "Summary / Root cause / Fix / Testing" write-ups (118669: 9 lines, 318 words). Several pre-empt the policy ("This is not an AI slop PR" 118545; "should be considered AI-assisted, not AI-authored" 119698). Outcome tracks provenance and permission more than code quality. Merged AI: 116640 (2,119 lines, linked to an accepted proposal), 117913 and 123234.

### Apache Kafka
- Sample: 104 PRs, no bots. 62 merged, 18 closed, 24 open (base 78%). Bias: 9 Generated-by hits + 24 merged controls + 5 slop/tool hits.
- AI share: 26 (25%), 24 strong. Merge rate AI 8/14 (57%) vs non-AI 54/66 (82%). Newcomers: AI 4/10 (40%) vs non-AI 23/33 (70%).

| Pattern | n | closed | merge rate present vs absent | closed examples | merged examples |
|---|---|---|---|---|---|
| Title not in Kafka form (`KAFKA-N:` / `MINOR:`) | 10 | 10/18 | 0% vs 89% | 20795, 20943, 21219 | none |
| Stray agent or report files (`DOCS-IMPROVEMENT-SUGGESTION.md`, `reports/*.md`, `AGENTS.md`) | 6 | 5 | 1/6 (the one merged is the maintainer AGENTS.md) | 22178, 22179, 22472 | 22470 |
| Identical PRs from one account in one burst (ClawOSS agent) | 4 | 4 | 0/4 | 22178, 22179, 22180 | none |
| Placeholder title (`KAFKA-XXXXX`, `[WIP]`) | 3 | 3 | 0/3 | 20795, 21219, 21339 | none |
| Conventional-commit title | 4 | 4 | 0/4 vs 82% | 22178, 22179, 22180 | none |

Not a problem here: Claude Code layout 3/3 merged (22491, 23444, 23465), long descriptions 85%, missing tests 83%.

Qualitative (6 closed AI, 8 merged AI read): merged AI PRs follow Kafka norms: JIRA key in title, `Generated-by:` or `Co-Authored-By:` trailer, `Reviewers:` line, tests (23832, `Generated-by: Claude Opus 5.5`, is the fix for KAFKA-21280 seen on the dev list). Closed AI PRs are autonomous-agent output (four identical ClawOSS "docs: suggest documentation improvements" PRs that only add a suggestion file) and "Unit test generated by Cline" (20943, empty body, closed in 2 minutes).

## 2. Cross-project patterns (3 or more projects)

Merge rate = merged/decided. "vs" = rate when the pattern is absent. curl figures are prevalence only. A dash means fewer than 2 decided PRs.

| Pattern | COSMIC | Ghostty | Godot | Kafka | Flink | curl (prevalence) |
|---|---|---|---|---|---|---|
| AI-flagged merge rate vs non-AI | 15% vs 28% | 55% vs 63% | 11% vs 56% | 57% vs 82% | 82% vs 94% | 8 PRs, 0 marked merged |
| Same, newcomers only | 15% vs 13% | 39% vs 41% | 11% vs 34% | 40% vs 70% | 75% vs 82% | n/a |
| Newcomer vs established author | 14% vs 62% | 40% vs 81% | 27% vs 86% | 63% vs 95% | 78% vs 93% | 139 newcomer PRs |
| Newcomer 500+ lines vs established 500+ | 0/4 vs 0/1 | 2/11 vs 7/9 | 2/14 vs 6/7 | 9/12 vs 6/6 | 4/6 vs 18/20 | 15 PRs, 0 merged |
| Newcomer 500+ lines and no linked issue | 0/1 | 1/9 | 0/7 | 1/3 | 0 PRs | 9 PRs |
| Closed within 1 hour (share of closed) | 5/48 | 26/43 | 24/72 | 2/18 | 0/11 | 24 |
| Conventional-commit title in a non-CC project | 15% vs 30% | 44% vs 63% | 1/2 | 0/4 vs 82% | 0 PRs | 12 |
| Title off project convention (incl. `KAFKA-XXXXX`) | n/a | n/a | n/a | 0/10 vs 89% | 11/11 merged (convention regex too strict) | n/a |
| Claude Code `Summary`/`Test plan` layout | 0/9 | 1/3 | 0/1 | 3/3 | 0 | 0 |
| Agent or planning files added (CLAUDE.md, AGENTS.md, *PLAN*/*SUGGESTION*/*REPORT*.md, notes/) | 0 | 0/2 | 0/2 | 1/6 | 1/1 (maintainer) | 0 |
| Description much longer than diff | 14% vs 27% | 56% vs 60% | 32% vs 53% | 85% vs 76% | 90% vs 89% | 19 |
| No tests for 20+ new source lines | 21% vs 26% | 54% vs 61% | 38% vs 51% | 83% vs 77% | 5/5 | 18 |
| Mandatory template item left (unticked attestation, placeholder title or text) | 0/4 | none | none | 0/4 | 12/12 merged | 0 |
| Wrong target or fork sync | 0 | 0/5 | 0/1 | 1 merged (false positive) | 0 | 0 |
| Patch hard markers (conflict markers, SEARCH/REPLACE, "... existing code") | 0 | 1 | 0 | 0 | 0 | 0 |

What actually recurs:
1. Author history is the strongest predictor everywhere (gaps of 15 to 59 points). The AI gap mostly disappears for newcomers in COSMIC and Ghostty, and persists in Godot, Kafka and, slightly, Flink.
2. Large changes from newcomers without an accepted issue are almost never merged in the three projects with an explicit permission policy (COSMIC, Ghostty, Godot: 1 of 17 combined), while established authors' large changes merge at 78% (Ghostty) and 86% (Godot). Apache projects merge large newcomer changes (13/18) because JIRA already gates them.
3. Fast closes (under an hour) are a big share of rejections in Ghostty and Godot (50 PRs). They are a mix of unrequested features, policy closes and accidental PRs. Maintainers spend that minute by hand every time.
4. Process-convention violations (wrong title form, conventional-commit titles in projects that don't use them, placeholder keys, unticked attestations, stray suggestion or report files) recur in COSMIC, Ghostty and Kafka with low merge rates.
5. Generic "quality smell" heuristics do not separate outcomes: missing tests, long descriptions, duplicated blocks, TODOs, CI edits, lockfile churn and patch markers are either rare or merged at baseline rates. Hard markers appeared in 1 of 879 PRs.

## 3. Flink and Kafka dev-list threads

All 9 stored threads were read. Each file holds only a starter excerpt of about 250 characters, so replies are not available. None contains a maintainer complaint about AI or low-quality PRs.

| List | Subject | Date | What it says | Short quote |
|---|---|---|---|---|
| Flink | Re: [PR] [FLINK-40775][ci] Add a PR template with AI disclosure [flink-ci-docker] | 2026-09-28 | Policy adopted: the AI disclosure template is extended to the flink-ci-docker repo (merged) | "1996fanrui merged PR #8" |
| Flink | Re: [DISCUSS] Flink Community Skills | 2026-10-11 to 10-16 | Proposal for a dedicated community repository of agent skills; +1 | "I agree with the proposal to create a dedicated skill repository. I have found them helpful as well." |
| Flink | Re: [DISCUSS] Flink Community Skills (second thread) | 2026-10-11 to 10-16 | +1 and a pointer to an earlier troubleshooting-skills proposal that went unpicked | "Jim Pizagno proposed operational troubleshooting skills for Flink on 4 August" |
| Flink | Re: [Discuss] FLIP-XXX: Dynamic Rate Limiting for FLIP-27 Source | 2026-10-08 to 10-14 | Off topic: LLM inference as a workload, not contributions | "pipelines involving large language model (LLM) inference" |
| Flink | [PR] [FLINK-41049][ci] Make pre-downloaded Maven usable... [flink-ci-docker] | 2026-10-21 | Off topic: retrieved because the PR template now carries the AI section | none |
| Kafka | [jira] [Created] KAFKA-21280 SslTransportLayer.transferFrom sends stale buffer contents... | 2026-10-14 | Bug report; the fix PR 23832 is AI-disclosed (`Generated-by: Claude Opus 5.5`) | none |
| Kafka | [jira] [Resolved] KAFKA-21280 | 2026-10-20 | Resolved by Jun Rao, fix version 4.5.0: a disclosed AI PR accepted through normal review | "Resolution: Fixed" |
| Kafka | Re: [PR] MINOR: Add 4.4 release [kafka-site] | 2026-10-19 to 10-20 | Reviewer finds more wrong version references in docs | "I found more wrong version reference in the docs here" |
| Kafka | Interest in an end-of-study internship (AI / ML / MLOps) at Kafka | 2026-10-16 | Off topic | none |

Policies visible in the PR data for these two projects (not mail):
- Flink #27776 (FLINK-39477, merged): adds `AGENTS.md` and an AI disclosure section to the PR template. The template text, present in 44 bodies, says: "Low-effort AI-generated PRs will be closed."
- Kafka #22470 (merged, co-authored by Claude): "MINOR: adding AGENTS.md". Disclosed AI PRs carry `Generated-by:` trailers and a `Reviewers:` line.

Maintainer statements from other projects' PRs, for context: Ghostty #10412 "AI generated contributions will only be allowed for accepted issues and maintainers" and "the 'bad' count by 10x if not more". Godot #118681: "Some even let the AI run loose and don't double-check its contributions." Godot #118823 (maintainer test): Claude Code "never looked at CONTRIBUTING.md and submitted the PR on my behalf before I could stop it". Ghostty #11539: the replaced AI PR's fixes "were outright incorrect".

## 4. Product opportunities

Each check is deterministic and runs on PR metadata, the diff and the project's own merged history. False-positive risk is the merge rate when the check fires vs the project baseline (a merged PR flagged is a false positive).

| # | Check or workflow | Evidence | FP risk |
|---|---|---|---|
| 1 | Permission gate: newcomer (no prior merged PR) + 500+ changed lines + no link to an issue in an accepted state. Hold for maintainer ack, reply with the policy. | COSMIC, Ghostty, Godot: 1 of 17 merged; established authors' large PRs merged 78 to 86%. | Low in permission-policy projects (11% Ghostty, 0% Godot, baselines 60% and 47%). High in Apache projects (Kafka 9/12 newcomer large merged); make the threshold per project and treat a JIRA key as the issue link. |
| 2 | Accepted-issue verifier: resolve the linked issue or proposal and check its state or label, not just that a link exists. | Ghostty and Godot policies require accepted issues; bare link presence is not predictive (Ghostty 58% vs 62%, Godot 43% vs 49%). | Low if it checks acceptance; high if it only checks for a link. |
| 3 | Author-history routing: first-time vs repeat contributor label and queue. Underpins checks 1, 2, 9. | Newcomer gap 15 to 59 points in all five usable projects. | Not a reject signal: newcomers still merge 14 to 78%. Use for routing only. |
| 4 | Project-convention linter learned from merged history: title form (`KAFKA-N:`, `MINOR:`, `[FLINK-N]`), placeholder keys (`KAFKA-XXXXX`), conventional-commit titles in projects that do not use them. | Kafka off-convention 0/10 vs 89%; placeholders 0/3; conventional titles COSMIC 15% vs 30%, Ghostty 44% vs 63%, Kafka 0/4. | Low for Kafka. My hand-written Flink regex fired on 11 merged PRs, so the rule must be learned from the last N merged titles, not hard-coded. Conventional titles: medium (Ghostty 44% still merge). |
| 5 | Stray-artifact check: new agent or planning files (`CLAUDE.md`, `AGENTS.md`, `*PLAN*.md`, `*SUGGESTION*.md`, `reports/`, `notes/`) from non-maintainers. | Kafka 1/6, Ghostty 0/2, Godot 0/2; examples 22178, 22472, 9919, 113175. | Low if CODEOWNERS or maintainers are exempt (Flink 27776 and Kafka 22470 added AGENTS.md on purpose). Volume is small. |
| 6 | Mass-submission and duplicate detector: same account opening PRs with identical title or body hash, and PRs from different authors claiming the same issue within days. | ClawOSS 4 identical PRs 0/4; Godot 124762 vs 124757; COSMIC one author 3 closed AI PRs in a week. | Very low for identical-body bursts. Bursts by themselves are not a signal (Ghostty bursts merge 73%, mostly maintainers). |
| 7 | Fast-triage assistant: pre-label the PRs maintainers close within an hour (wrong target, fork sync, unrequested feature, policy) and draft the closing reply. | 50 fast closes in Ghostty and Godot, 22/26 and 24/24 newcomers. | Advisory only; it saves the triage minute rather than deciding. |
| 8 | Mandatory-template check: only items maintainers enforce (attestation boxes, issue key, AI disclosure field) rather than the whole template. | COSMIC unticked attestation 0/4; Kafka template text left 0/1. | Low for mandatory items. High for full-template completeness: Flink merged 12/12 PRs with placeholders left. |
| 9 | Disclosure normaliser: detect agent fingerprints (Claude Code footer, `Co-Authored-By` AI, Summary/Test-plan layout, Generated-by) and ask for the project's disclosure format when missing. Route, never auto-reject. | Fingerprints are cheap and common (COSMIC layout 0/9 merged). | High as a reject rule: Kafka layout 3/3 merged, Flink disclosed AI 82% merged, Ghostty maintainers disclose AI. |
| 10 | Wrong-target / fork-sync detector: title patterns ("Sync/upstream", "wrong repo"), head branch equal to upstream default, thousands of lines of upstream history. | Ghostty 0/5, Godot 0/1. | Low (one Kafka false hit, 21248); small volume. |
| 11 | Scope checks for dependency churn: lockfile-only or `npm audit fix` regenerations over N lines from non-maintainers. | Flink 27997 (13,748 lines) and 28000 (18,113) closed after months. | Medium; only two cases. |
| 12 | Hard-marker scan (conflict markers, SEARCH/REPLACE blocks, "... existing code" elisions). | 1 of 879 PRs (Ghostty 13408). | Near zero FP, near zero recall need. Cheap to include, not a selling point. |

Not worth building as gates on this evidence: missing-tests, long-description, duplicated-block and TODO checks. They fire on merged PRs at or near baseline rates (for example, missing tests: Kafka 83% vs 77%, Flink 5/5 merged).

## 5. Honest limits

- The sample is search-driven, not random. AI-flagged PRs and merged controls are over-represented. Rates are comparisons inside a biased sample, not population estimates. Population totals in the manifests come from GitHub search counts.
- Small cells: many patterns have 2 to 10 PRs. Flink has only 11 closed PRs; COSMIC 15 merged. Treat single-digit counts as anecdotes.
- curl's merge flag is not meaningful (land-by-push). I excluded curl from every merge-rate claim.
- No review comments, close reasons, labels, CI status, author association or commit messages are stored. "Newcomer" is inferred from this sample, so some regulars look like newcomers. Close reasons (policy, stale, superseded, wrong target) are inferred from body text and timing only.
- AI detection relies on self-disclosure and tool names in the text. Undisclosed AI PRs are counted as non-AI; PRs that discuss LLM features can be miscounted. Template boilerplate was stripped by pattern; some may remain.
- Patches are missing for 36 too-big PRs, and file-level checks (tests, artifacts, duplicates) do not see them.
- Heuristics were checked by hand on samples: the first "placeholder code" rule matched UI placeholder strings and the first duplicate rule matched maintainers editing the same CI file, so both were tightened. The remaining heuristics (title/diff mismatch, duplicated blocks, long descriptions) are approximate.
- Mail threads hold only roughly 250-character starter excerpts, and 4 of 9 are off topic. Section 3 cannot represent the full dev-list discussion; the policies there come mostly from PR data.
- Open PRs (133) are excluded from merge rates. Some will merge later, which would shift rates for recent patterns.
