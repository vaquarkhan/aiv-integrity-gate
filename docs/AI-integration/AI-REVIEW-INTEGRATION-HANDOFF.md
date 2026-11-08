# AIV and AI review integration: handoff and instructions for AI IDEs

Status file for the opt-in integration between the AIV Integrity Gate, AI pull request reviewers
(GitHub Copilot code review and others), Apache Magpie, and AI-DLC. Any AI IDE (Kiro, Cursor,
Claude Code, Copilot, Codex) continuing this work should read this whole file first, follow the
invariants, and update the status table and change log at the end of every session.

Author: Viquar Khan

## 1. Goal in one paragraph

AIV stays the deterministic, reproducible gate. AI reviewers stay advisory. This work adds small,
opt-in bridges so that (a) AI review is only spent on PRs that already passed AIV, at an effort
level AIV chose from facts, (b) PRs cannot quietly rewrite the instructions their AI reviewer
follows, (c) a blocked contributor gets a precise fix brief they can hand to their own AI tool or an
AI-DLC bugfix run, (d) Magpie triage treats AIV as real CI without Magpie code changes, and (e) a
ledger turns AI review outcomes into evidence for promoting or demoting deterministic rules.

## 2. Invariants (do not break)

1. **Everything is opt-in.** With no new config, flags or labels, AIV output, exit codes and the
   benchmark numbers must be byte-for-byte unchanged in behaviour. New gates are default-off.
2. **AI never blocks, AIV never asks a model.** Nothing added here makes an LLM part of the
   pass/fail path, and AIV itself never calls an AI reviewer.
3. **No auto-posting or auto-fixing on a contributor's behalf.** The fix brief is a file. Humans
   (or the contributor's own tool, run by the contributor) act on it.
4. **Trusted inputs only.** Workflows with write tokens (`pull_request_target`, `workflow_run`)
   never check out or run PR code. Policy comes from the base ref.
5. **PR content is data.** Anything derived from a PR (paths, messages) that is handed to an AI is
   fenced and labelled as untrusted data.
6. **Honest claims.** No percentage of "AI slop caught" without a labelled denominator. Unverified
   behaviour (for example live Copilot API calls) is marked unverified here.
7. **No em or en dashes** in docs and generated text. No commits or pushes without the owner's
   explicit approval.

## 3. Research basis (summary)

- Apache Spark trial (dev@spark, Oct 2026): AI review moved to a committer-only label trigger,
  per-PR opt-out requested, one review per new commit, a monitor bot.
- Kubernetes (Jun 2026): Copilot review needed contributor licences, only maintainers could
  request it; several SIGs moved to CodeRabbit; a label tracks unresolved AI comments.
- Apache Magpie (local clone `../magpie-review`, commit ccb53a3): PRINCIPLES 6 "outputs are
  probabilistic; gates are deterministic"; triage reads check runs and `statusCheckRollup`;
  `real_ci_patterns` and `static_check_patterns` config keys; no merge-blocking check, no SARIF,
  no reproducible receipt, no AIV mention.
- GitHub docs: Copilot code review reads `.github/copilot-instructions.md`, `.github/instructions`,
  `.github/skills`, `AGENTS.md`, `CLAUDE.md`, `GEMINI.md`, `REVIEW.md` from the PR head branch.
- Pillar Security "Rules File Backdoor" and CSA "Comment and Control": hidden Unicode and PR text
  can steer AI tools. `agentcheck` (DhanushNehru) already scans instruction files for content.

## 4. Components

| # | Component | Where | Default |
|---|---|---|---|
| A | `invariant.review-instruction-change` rule | `aiv-plugin-invariant-template` | off until `review_instruction_paths` set |
| B | `invariant.hidden-unicode` rule | `aiv-plugin-invariant-template` | off until `hidden_unicode` set |
| C | `review-routing` gate (deterministic review tier) | `aiv-plugin-review-routing` | default-off gate |
| D | `--label-review-tier` publisher | `aiv-cli`, `aiv-adapter-github` | off until flag |
| E | `--output-fix-brief` writer | `aiv-cli` | off until flag |
| F | Copilot review request workflow and decision script | `.github/workflows/copilot-review.yml`, `.github/scripts/copilot-review/` | label mode |
| G | Magpie integration snippets and proof | `integrations/magpie/` | documentation only |
| H | Review ledger (record and report) | `integrations/review-ledger/` | separate tool |
| I | AI-DLC handoff | `integrations/aidlc/` (existing sensor) plus fix brief | documentation |

## 5. End-to-end flow

```text
Contributor (optional: AI-DLC with the AIV sensor before opening the PR)
  -> PR -> AIV Gate (required status check, JSON/SARIF receipt)
       review-routing gate -> label aiv:review-<tier>        (C, D)
       BLOCK -> aiv-fix-brief.md artifact                     (E)
             -> contributor feeds it to their own AI tool or `/aidlc --scope bugfix`
             -> push -> AIV again
       PASS  -> copilot-review.yml decides                    (F)
                tier none -> no AI review
                tier lite / balanced -> request Copilot with the matching token
             -> Magpie triage reads the AIV check as real CI  (G)
             -> maintainer decides
  -> review ledger records AIV findings and AI review outcomes (H)
       -> report proposes rule promotions/demotions for a human to approve
```

## 6. Status

| Task | State | Evidence |
|---|---|---|
| A instruction-file rule | done, opt-in | `InvariantGateConfigTest` (paths, rename, delete, excluded files, disable); e2e scenario 3 |
| B hidden-unicode rule | done, opt-in | `InvariantGateConfigTest` (each character class, legitimate BOM/emoji/RTL, added lines only, scopes); e2e scenarios 2 and 3 |
| C review-routing gate | done, default-off | `ReviewRoutingGateTest` (rules, excluded/renamed paths, config errors); e2e scenarios 4 and 5 |
| D tier label publisher | done, opt-in flag | `GithubReviewTierLabelPublisherTest` (fake API: replace, no-op, prefix, errors); `MainTest.reviewRoutingGateTierIsPublishedAsALabel`; e2e scenario 6 |
| E fix brief | done, opt-in flag | `FixBriefWriterTest` (sanitising, caps, wording); `MainTest.outputFixBriefWritesBriefForPassingRun`; e2e scenario 1 |
| F Copilot workflow tier wiring | done | `decide.test.js` 25 tests, 99.7% lines |
| G Magpie integration | done, config only | `integrations/magpie/test_magpie_with_aiv.py` 6 tests against Magpie's real code (commit ccb53a3) |
| H review ledger | done, separate tool | `integrations/review-ledger/test_aiv_review_ledger.py` 7 tests |
| Final verification | see change log | full reactor build, e2e jar runs, benchmark regression |

**Not verified (needs a live GitHub repository):** the Copilot request and re-request calls, per-tier
token effort selection, the example ledger workflow, and Magpie running end to end against a real PR.
The GitHub REST calls made by AIV itself are verified only against local fake servers.

## 6a. Configuration reference

| Switch | Where | Effect |
|---|---|---|
| `review_instruction_paths: [...]` | invariant gate `config` | enables `invariant.review-instruction-change` |
| `hidden_unicode: off \| instruction_files \| all_files` | invariant gate `config` | enables `invariant.hidden-unicode`; `instruction_files` uses `review_instruction_paths` or a built-in list |
| `- id: review-routing` `enabled: true` | `.aiv/config.yaml` | emits `review-routing.tier` |
| `--label-review-tier [prefix]` or `AIV_LABEL_REVIEW_TIER=true` | CLI / Action `label-review-tier` | sets one `aiv:review-<tier>` label; exit 2 if the gate is off or GitHub env is missing |
| `--output-fix-brief <path>` | CLI / Action `output-fix-brief` | writes the Markdown brief |
| `AIV_COPILOT_MODE`, `AIV_COPILOT_REQUEST_LABEL`, `AIV_COPILOT_OPT_OUT_LABEL`, `AIV_CHECK_NAME`, `AIV_REVIEW_INSTRUCTION_PATHS`, `AIV_REVIEW_TIER_PREFIX`, `AIV_NO_REVIEW_TIERS`, `AIV_COPILOT_REQUIRE_TIER` | repository variables | decision script behaviour |
| `COPILOT_REVIEW_TOKEN`, `COPILOT_REVIEW_TOKEN_<TIER>` | repository secrets | requesting account; per tier selects effort |

## 6b. Key files

- `aiv-plugin-invariant-template/.../InvariantGate.java`: rules A and B (`HIDDEN_UNICODE`, `DEFAULT_INSTRUCTION_PATHS`, `matchesReviewInstructionPath`).
- `aiv-api/.../AIVContext.java`: `EXCLUDED_CHANGED_FILES` metadata key; set in `aiv-core/.../Orchestrator.java`.
- `aiv-plugin-review-routing/`: gate C. Registered in the root `pom.xml`, `aiv-cli/pom.xml`, and `DEFAULT_OFF_GATES`.
- `aiv-adapter-github/.../GithubReviewTierLabelPublisher.java`: publisher D (reuses package helpers in `GithubPrLabelPublisher`).
- `aiv-cli/.../FixBriefWriter.java` and `Main.java` flags: E and D wiring. Hints come from `GithubPrReviewCommentsPublisher.hintFor`.
- `.github/scripts/copilot-review/decide.js`, `.github/workflows/copilot-review.yml`, `.github/skills/code-review/SKILL.md`, `.github/copilot-instructions.md`: F.
- `integrations/magpie/`, `integrations/review-ledger/`, `integrations/aidlc/` (existing sensor): G, H, I.
- `action.yml`: inputs `label-review-tier`, `output-fix-brief`.
- `docs/pipeline-aiv-copilot.md`: user-facing guide.
- `docs/AI-integration/MAGPIE-AND-AIV-TOGETHER.md`: how Magpie and AIV complement each other.
- `docs/AI-integration/AI-REVIEW-INTEGRATION-SOURCE.md`: generated complete source, from
  `scripts/generate-ai-review-source-appendix.py`.
- `scripts/e2e-ai-review-integration.py`: end-to-end check through the real jar.

## 6c. Next ideas (not started)

1. Run the Copilot workflow on a sandbox repository with a licensed token and record the real
   request/re-request responses here.
2. Feed the ledger from a few months of a real project and publish the first promotion/demotion report.
3. Let `review-routing` read selected rule hits from other gates (needs an Orchestrator post-pass,
   because gates cannot see each other's results).
4. Change policy engine phase 2: reuse facts against the repository (`aiv-analysis`), `hits.*` from
   other gates, risk score into routing. Phase 1 is done (component J below).

## 6d. Component J: change policy engine (done, phase 1)

Opt-in gate `change-policy` in `aiv-plugin-change-policy`: review budgets by author tier and area,
untested source changes, title versus diff, description claims, template, disclosure and history rules,
as declarative policies over deterministic facts. Design, decisions and calibration:
[CHANGE-POLICY-ENGINE-DESIGN.md](CHANGE-POLICY-ENGINE-DESIGN.md); evidence:
[MAINTAINER-PAIN-AND-PR-PATTERNS-ANALYSIS.md](MAINTAINER-PAIN-AND-PR-PATTERNS-ANALYSIS.md).

- Core change: `GateResult` can name rules it rates advisory inside a blocking result
  (`getAdvisoryRules()`), and `Orchestrator.applyRuleActions` uses `warn` as their default. Without this,
  one promoted policy turned every other fired policy blocking. Project `rule_actions` still win.
- `action.yml` exports `AIV_PR_LABELS`, `AIV_PR_AUTHOR_ASSOCIATION`, `AIV_PR_DRAFT`.
- Calibration on 1,991 stored PRs with the real gate:
  `python benchmarks/airflow/scripts/run-change-policy-calibration.py` (report in
  `benchmarks/airflow/reports/change-policy-calibration-latest.md`).
- End to end through the jar: `python scripts/e2e-change-policy.py` (8 scenarios).
4. Offer the Magpie rows upstream as a documented example once a project has used them.

## 7. How to build and test

- JDK 17, Maven at `C:\tools\apache-maven-3.9.11\bin\mvn.cmd` on the author's machine
  (`mvn` on PATH elsewhere). Never run two Maven reactors at once in this repo.
- Full build: `mvn -B -ntp clean verify` (JaCoCo gates: aiv-api 100% lines, aiv-adapter-git 99%).
- CLI jar: `mvn -pl aiv-cli -am clean package -DskipTests` then `aiv-cli/target/aiv-cli-1.0.4.jar`.
- Decision script tests: `node --test .github/scripts/copilot-review/decide.test.js`.
- Python tools: `python -m pytest integrations/` (Magpie proof needs a Magpie checkout; set `MAGPIE_ROOT`).
- End to end through the real jar and real Git repos: `python scripts/e2e-ai-review-integration.py`
  (six scenarios: default policy unchanged, conflict plus fix brief, hidden Unicode and instruction
  change, two routing tiers, label flag failing loudly outside GitHub).
- Benchmark regression (must stay 35/0/0/15, 2/2, 1 TP + 1 TN):
  `python benchmarks/airflow/scripts/run-structural-benchmark.py --jar aiv-cli/target/aiv-cli-1.0.4.jar --output <tmp>`,
  `python benchmarks/airflow/scripts/run-labeled-benchmark.py`,
  `python benchmarks/airflow/scripts/run-selected-real-airflow.py --jar aiv-cli/target/aiv-cli-1.0.4.jar`.

## 8. Change log

- Session 1: research (Spark thread, Kubernetes, LLVM, Node.js, ASF, Magpie local clone,
  agentcheck, Rules File Backdoor). Prototype of A and F written before the design was agreed;
  kept uncommitted.
- Session 2 (design agreed in chat, implemented):
  - A made strictly optional: removed the active `review_instruction_paths` block from both Airflow
    presets; the root preset keeps a commented snippet.
  - B, C, D, E implemented with unit tests; F extended with tiers and per-tier tokens; G and H added
    under `integrations/`; `action.yml` gained two opt-in inputs; docs updated.
  - Defect found and fixed during e2e work: `exclude_paths: "*.md"` hid a root `AGENTS.md` from
    gates, so instruction files are now passed to gates via `EXCLUDED_CHANGED_FILES` and checked for
    rules A and B only.
  - JaCoCo caught one uncovered `Main` line (a call that only ever threw in tests); added a fake-API
    CLI test for the tier label.
  - Final verification on 2026-10-23: `mvn -o clean install` BUILD SUCCESS, 26 modules, all JaCoCo
    gates met (aiv-cli 167 tests, aiv-adapter-github 69, aiv-plugin-invariant-template 41,
    aiv-plugin-review-routing 6, aiv-core 78, aiv-api 80); `decide.test.js` 25/25;
    `pytest integrations` 21 passed; `scripts/e2e-ai-review-integration.py` ALL E2E OK;
    `scripts/validate-docs.py` OK; no em or en dashes in touched files.
  - Benchmark regression with the rebuilt jar: fixtures TP 35 / FN 0 / FP 0 / TN 15, advisories 5/5;
    structural 2/2 with 0 false blocks; selected Airflow #59957 TP and #70842 TN, 0 errors. The
    labeled run regenerated `benchmarks/airflow/reports/labeled-latest.*` with the same numbers.
  - No commits or pushes.

## 9. Recreate this work from scratch (instructions for another AI IDE)

The complete source is in [AI-REVIEW-INTEGRATION-SOURCE.md](AI-REVIEW-INTEGRATION-SOURCE.md): Part 1
has every new file in full (code, tests, workflows, docs), Part 2 has the exact changed regions of
files that already existed, with today's line numbers. That file is generated; regenerate it with
`python scripts/generate-ai-review-source-appendix.py` after any change and keep
`--check` green. How Magpie and AIV are meant to be combined is in
[MAGPIE-AND-AIV-TOGETHER.md](MAGPIE-AND-AIV-TOGETHER.md).

Work in this order. Each step lists the files to create or edit (all in the source appendix) and the
check that must pass before moving on. Do not batch steps; a failing check means stop and fix.

| Step | Do | Files | Must pass |
|---|---|---|---|
| 1 | Add the `EXCLUDED_CHANGED_FILES` key and pass excluded files from the Orchestrator; extend the test gate | `AIVContext.java`, `Orchestrator.java` (region), `AssertChangedFilesGate.java`, `OrchestratorTest.java` (region) | `mvn -pl aiv-api,aiv-core -am install` |
| 2 | Rules A and B in the invariant gate | `InvariantGate.java`, `InvariantGateConfigTest.java` | `mvn -pl aiv-plugin-invariant-template install` (41 tests) |
| 3 | New module `aiv-plugin-review-routing` and its registration | module `pom.xml`, service file, `ReviewRoutingGate.java`, test, README, root `pom.xml` and `aiv-cli/pom.xml` (regions), `DEFAULT_OFF_GATES` (region) | `mvn -pl aiv-plugin-review-routing install` (6 tests); `python scripts/validate-docs.py` |
| 4 | Tier label publisher | `GithubPrLabelPublisher.java` (regions), `GithubReviewTierLabelPublisher.java`, its test | `mvn -pl aiv-adapter-github install` (69 tests) |
| 5 | Fix brief and the hints it reuses | `GithubPrReviewCommentsPublisher.java` (regions), `FixBriefWriter.java`, its test | builds in step 6 |
| 6 | CLI flags and wiring | `Main.java` (regions), `MainTest.java` (regions) | `mvn -pl aiv-cli -am install` (167 tests, 100% line coverage) |
| 7 | Action inputs and preset snippet | `action.yml` (regions), `presets/airflow/config.yaml` (region) | YAML parses; presets still have no active new config |
| 8 | Copilot workflow, decision script, skill, instructions | `.github/workflows/copilot-review.yml`, `.github/scripts/copilot-review/decide.js` and test, `.github/skills/code-review/SKILL.md`, `.github/copilot-instructions.md` | `node --test .github/scripts/copilot-review/decide.test.js` (25 tests) |
| 9 | Magpie integration | `integrations/magpie/*` | `MAGPIE_ROOT=<clone> python -m pytest integrations/magpie` (6 tests) |
| 10 | Review ledger | `integrations/review-ledger/*` | `python -m pytest integrations/review-ledger` (7 tests) |
| 11 | End to end and docs | `scripts/e2e-ai-review-integration.py`, `docs/pipeline-aiv-copilot.md`, this file, `docs/AI-integration/MAGPIE-AND-AIV-TOGETHER.md` | `python scripts/e2e-ai-review-integration.py` prints ALL E2E OK; `mvn clean verify` green; benchmark numbers unchanged (section 7) |
| 12 | Change policy engine (component J) | `GateResult.java`, `Orchestrator.java`, their tests (regions); every `aiv-plugin-change-policy` file; registration in both `pom.xml` files and `DEFAULT_OFF_GATES`; `action.yml` (region); calibration and e2e scripts | `mvn -pl aiv-api,aiv-core,aiv-plugin-change-policy,aiv-cli -am install` (100% line coverage); `python scripts/e2e-change-policy.py` ALL E2E OK; calibration table matches design section 14; benchmark numbers unchanged |

Prompt to give another AI IDE, verbatim:

```text
Read docs/AI-integration/AI-REVIEW-INTEGRATION-HANDOFF.md fully, then docs/AI-integration/AI-REVIEW-INTEGRATION-SOURCE.md.
Recreate the work in the order of section 9. For files in Part 1, write them exactly as shown.
For Part 2 regions, apply the shown code at the described place in the existing file; do not
change unrelated code. After each step run its check and stop on failure. Keep every feature
opt-in, use no em or en dashes, and do not commit or push. When done, run
scripts/generate-ai-review-source-appendix.py --check and report each check's result.
```

Gotchas seen while building it:

- `exclude_paths` hides files from gates. Instruction files are often Markdown (`*.md` is excluded in
  the Airflow preset), which is why step 1 exists.
- JaCoCo marks a line as missed when the only test reaching it throws inside the call. `aiv-cli`
  requires 100% lines, so the tier label needs the fake-API test in `MainTest`.
- `javadoc` ends at `*/`, so a glob like `**/AGENTS.md` inside a Javadoc `<pre>` block must be written
  as `**&#47;AGENTS.md`.
- Magpie matches `static_check_patterns` as a substring in either direction; use the full AIV job name.
- A 0 out of 12 acted-on rate still has a 95% Wilson upper bound near 0.24, so the ledger correctly
  refuses to propose retiring a rule on 12 PRs with the default 0.2 threshold.
- In PowerShell, never chain a cleanup `Remove-Item` after a command that might fail with `;`.
