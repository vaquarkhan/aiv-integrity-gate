# Product changes: Apache-facing AIV Gate work

A running log of every change made to shape AIV into a foundation-wide,
ASF-aligned gate. This file is the instruction/changelog the work is tracked
against. Nothing here is committed or pushed to GitHub; all changes are local.

Author of record for this work: Viquar Khan (vaquar.khan@gmail.com).

---

## Ground rules for this work

- **Read-only against GitHub.** No commits, no pushes. Local file changes only.
- **Precision-first.** Every new hard rule must be objective and string-decidable;
  when inputs are missing or ambiguous, the gate passes.
- **No LLM on the blocking path.** Deterministic checks only.
- **Coverage.** The parent POM enforces 100% line coverage per module on
  `verify`. Every new module ships with complete tests before it is called done.
- **Honesty.** AIV is `io.github.vaquarkhan`, not an ASF project. The "Apache"
  framing is a goal pending donation/adoption, recorded in the analysis doc.

---

## Change log

### 0. Research and design docs (already on disk)

- `docs/apache/APACHE-AIV-GATE-ANALYSIS.md`: the foundation-wide problem
  catalog, decidability triage, and gate design.
- `docs/apache/APACHE-ADOPTION-ROADMAP.md`: the phased adoption roadmap.
- Deep research completed against ASF primary sources: Generative Tooling
  Guidance, the "AI Agents and AGENTS.md Files" draft, the 3rd-party license
  policy (Category A/B/X), src-headers policy, release policy, ATR release-time
  checks, Apache RAT, Apache Magpie, and per-project AI policies
  (apache/fory AI_POLICY.md, Wayang). Key extracted, deterministically-checkable
  rules: PR title/body hygiene, Gen-AI disclosure block presence, AI attribution
  commit trailers (`Assisted-by`/`Generated-by`), Apache license header on added
  files, Category-X dependency denylist.

### 1. (DONE, verified green) `pr-metadata` gate

New Maven module `aiv-plugin-prmeta`, gate id `pr-metadata`. Deterministic
pull-request title/body hygiene plus Gen-AI disclosure presence. Aligned to
Airflow's documented PR quality criteria and the apache/fory AI policy.

Rules (all precision-first; pass when inputs are absent):
- title: empty, conventional-commit prefix (`feat:`/`fix:`...), issue-number-only,
  generic (`fix bug`, `update code`...), shorter than `min_title_words`
- body (only when supplied): empty, repeats-title, template-only (needs the PR
  template)
- provenance: `require_disclosure: true` fails when no Gen-AI disclosure marker
  is present (off by default; only asserts presence)

Inputs resolve from `AIVContext` metadata (`pr_title`/`pr_body`/`pr_template`)
then environment (`AIV_PR_TITLE`/`AIV_PR_BODY`/`AIV_PR_TEMPLATE`). An injectable
env seam keeps it unit-testable. Note: the existing `invariant` gate already
covers AI attribution trailers pasted into source, so provenance here is the
disclosure-presence half.

Files:
- `aiv-plugin-prmeta/pom.xml`, `README.md`
- `.../prmeta/PrMetadataGate.java`
- `.../META-INF/services/io.github.vaquarkhan.aiv.port.QualityGate`
- `.../prmeta/PrMetadataGateTest.java`
- registered in root `pom.xml` (`<modules>` + `<dependencyManagement>`) and
  `aiv-cli/pom.xml`

Verification: `mvn -pl aiv-plugin-prmeta -am clean verify` -> 18 tests pass,
100% line coverage (103/103). Full `mvn -pl aiv-cli -am clean verify` green.

### 2. (DONE, verified green) `asf` preset

New config preset selectable via `aiv init --preset asf`. Hard gates for
objectively-broken artifacts (syntax, invariant, dependency, security); advisory
(`severity: warn`) for pr-metadata, license-header, density, cohesion, per the
roadmap's advisory-first rollout.

Files:
- `aiv-cli/src/main/resources/presets/asf/config.yaml` (loaded by `InitCommand`)
- `presets/asf/config.yaml` (human-facing mirror)
- `presets/README.md` (table row)
- `InitCommand.java` (known-preset list + javadoc)

### 3. (DONE, verified green) `license-header` gate

New Maven module `aiv-plugin-license-header`, gate id `license-header`.
Diff-scoped Apache license-header check on newly ADDED source files, RAT-aligned
but shifted left to the PR. Checks the file head for the standard Apache header
text or the SPDX short form. Skips modified files, generated artifacts (by suffix
and by generated-marker content), blank files, and non-source extensions.
Precision-first.

Files:
- `aiv-plugin-license-header/pom.xml`, `README.md`
- `.../licenseheader/LicenseHeaderGate.java`
- `.../META-INF/services/io.github.vaquarkhan.aiv.port.QualityGate`
- `.../licenseheader/LicenseHeaderGateTest.java`
- registered in root `pom.xml`, `aiv-cli/pom.xml`, and both `asf` preset copies

Verification: `mvn -pl aiv-plugin-license-header -am clean verify` -> 13 tests
pass, 100% line coverage (45/45).

### 4. (DEFERRED, documented) `dependency` Category A/B/X

Not built this session, deliberately. A robust Category-X check needs either
dependency-license resolution (better served by SBOM at release, which is ATR's
lane) or a curated coordinate denylist that must be calibrated to stay zero-FP.
Shipping a thin denylist now would be tokenistic and risk false positives, which
violates the precision-first principle. Recommended as the next increment: a
small, well-tested `license-category` gate that flags a NEW dependency
coordinate added to a build file only when it matches a curated Category-X
denylist, defaulting to advisory. Tracked in the roadmap.

### Build/runtime notes

- Build with the portable Maven: `C:\tools\apache-maven-3.9.11\bin\mvn.cmd`
  (`mvn` is not on PATH). JDK 17 is the system default. Run builds as a
  background process and redirect all streams to a log file (`*> build.log`),
  then read the log; the interactive console truncates.
- The parent POM enforces 100% BUNDLE line coverage on `verify`; every new
  module ships complete tests.
- Both new gates default-enable in the orchestrator (unknown gate ids default on
  except `doc-integrity`/`security`), but both PASS when their inputs are absent,
  so existing users and local pre-commit runs are unaffected.

### 5. (DONE, verified green) Orchestrator: make new ASF gates opt-in

`aiv-core/.../Orchestrator.java`: added `license-header` and `pr-metadata` to the
default-off gate set (alongside `doc-integrity` and `security`). ServiceLoader
still discovers them, but they only run when a project enables them (directly or
via the `asf` preset).

Why: the first full reactor run after adding `license-header` failed two existing
`MainTest` cases (`diffJsonRunsWithoutGit`, `publishPrCommentsFlagInvokesPublisher`)
because the gate was default-on and hard, so it fired on their added no-header
fixtures. Making the new ASF gates opt-in restores prior behavior for anyone who
has not adopted the `asf` preset, and matches the precision-first promise.

### Final verification

`mvn clean verify` across the full reactor: EXIT_CODE=0. All modules pass with
100% line coverage enforced. New module test counts: `aiv-plugin-prmeta` 18,
`aiv-plugin-license-header` 13. No temporary build logs left in the tree; nothing
committed or pushed.

### Remaining / next increment

- `dependency` Category A/B/X gate (deferred, see entry 4).
- `.aiv/asf-overrides.yaml` contract, reusable GitHub workflow, and `.rat-excludes`
  reuse (design in the analysis doc, not yet wired).
- Calibration harness run against real ASF merged-PR history to prove
  zero-false-positive before recommending any gate on the hard path.

### 6. Due diligence: MCP-Bastion paper + external strategy analysis

Two documents were reviewed for what they contribute to the AIV Apache work.

**MCP-Bastion paper ("The Forecaster Cannot Approve Its Own Build", Paper 24,
V. Khan).** Sibling work by the same author; it cites AIV as corroboration
(ref [44]). It is directly useful and validating, not redundant:
- Its central thesis - advisory guidance (instruction files, prompts) is
  structurally insufficient because a model cannot certify its own compliance,
  so you need an independent deterministic verifier with HITL override and a
  tamper-evident audit - is the same argument that justifies AIV over
  AGENTS.md/Magpie-prompts. Reuse it verbatim in the "why deterministic" pitch.
- Its exact-checks-carry-guarantees vs heuristic-checks-are-defense-in-depth
  split is exactly AIV's design stance; the paper states it formally.
- Empirical data point to cite: a live study across six frontier models found a
  **25% advisory-only violation rate** (and ~26.7% even with an emphatic system
  prompt). This is external evidence for AIV's RQ1 ("rule-guided agents still
  emit violations") and belongs in the roadmap's motivation.
- Two concrete features worth adopting into AIV (added to the analysis doc's
  deferred list): (a) a **hash-chained, tamper-evident audit** of gate verdicts
  and HITL overrides (SHA-256 chain; detects retroactive edits/deletions) -
  strong for ASF auditability, EU AI Act Art. 14, and NIST AI RMF; (b) an
  **accountable HITL override** record (identity + reason + reproduce command).

**External strategy analysis (Gemini).** Directionally sound on incubation
(Software Grant, ICLA/CCLA, three IPMC mentors, "incubating" release artifacts,
Magpie synergy, SLSA/SBOM alignment, shift-left circuit breaker). But it makes
claims that must NOT be propagated without correction (verified against this
repo):
- FALSE: "AIV utilizes ... libraries (such as Lucene)." Grep of every `pom.xml`
  finds no Lucene dependency. Do not repeat.
- NOT PRESENT: `aiv-plugin-podling` and `aiv-plugin-consensus`. The document
  floats them as proposals (acceptable as ideas) but they do not exist today;
  label them clearly as unbuilt if reused.
- OVERSTATED: AIV "parses the AST to evaluate density." Confirmed in code: the
  density gate's logic-density (LDR) uses JavaParser and is **Java-only**; other
  languages get a Shannon-entropy check only. The document itself later concedes
  Python AST support must mature - internally inconsistent. Python LDR is a real
  gap, not a shipped feature.
- UNVERIFIED: the characterization of "AIP-120" as forced fork-based CI
  execution, and the AIP-118 cost figures. Primary sources I read
  (`05_pull_requests.rst`, `25_maintainer_pr_triage.md`) describe PR quality
  criteria + Magpie triage, not fork-CI. Treat the AIP numbers as needing
  confirmation before any external claim.

Net: the paper strengthens the thesis and hands us a concrete audit feature and
a citable 25% data point; the strategy analysis is a usable incubation narrative
once the four items above are corrected. Vetted ideas promoted to the analysis
doc's "deferred / next" list: hash-chained verdict audit, and an ASF podling
gate (incubating naming, DISCLAIMER, header checks at PR time).

### 7. Dogfood: AI-scaffolding check on the newly written code

Ran the shipped gate on my own two new gate source files (the density and
invariant pillars are precisely the AI-scaffolding / slop detectors), via the
CLI diff-json mode against the built `aiv-cli-1.0.4.jar`:

- Inputs: `PrMetadataGate.java`, `LicenseHeaderGate.java` (as ADDED files).
- Gates run: density, invariant, syntax, license-header.
- Result: **Overall PASS, exit 0.** density PASS, invariant PASS, syntax PASS,
  license-header PASS.

Interpretation: the new code carries real logic density (no low-entropy
scaffolding), no placeholder/merge/AI-edit-artifact markers, parses cleanly, and
already has the Apache header. No AI-slop remediation needed. The dogfood was run
from a scratch workspace and its temporary files were removed afterward (no
scratch left in the tree, nothing committed).

### 8. Red-team / hostile-PMC objection pass

Added `docs/apache/RED-TEAM-OBJECTIONS.md`: an adversarial stress test written
from the seat of a -1 PMC voter, pushing 12 objections to bedrock and converging
on the irreducible defensible core. Key outcomes recorded there:

- Claims that DIE and must never be made: "solves AI slop," "saves CI cost,"
  "should be a TLP / donate now," "better linter than prek/ruff/Sonar," "trust
  our zero-FP number," "decides EU AI Act compliance," "generic-title/density are
  guarantees."
- The surviving core: for the string-fact subset of ASF AI-provenance
  obligations, AIV gives a deterministic, model-free, locally-reproducible verdict
  plus a tamper-evident record that no ASF tool produces and an LLM tool
  structurally cannot; everything softer is advisory and calibrated per-repo;
  it is opt-in.
- The only motion winnable today: an advisory-only, non-blocking, calibrated
  trial on one repo - not adoption or donation.
- Converts to real "pull" only with: traction (1-2 projects, one Python-heavy),
  Python-first parity, the tamper-evident audit built, an independent calibration
  corpus, and neutral (non-"Apache") framing.

This supersedes the earlier framing in the analysis/roadmap that leaned on
"foundation-wide code-quality standard"; the honest wedge is the
deterministic + auditable provenance-evidence layer under the LLM/judgment layer.

### 9. Fixes to remove attack surface (not defenses - actual changes)

The red-team found two real surfaces. Instead of arguing them, the product was
changed so the arguments have nothing to bite.

**Fix A - build the one non-redundant thing: tamper-evident audit.**
New module `aiv-audit` (`HashChainAudit`, `VerifyResult`). Append-only SHA-256
hash chain over verdict/override payloads (`h_i = SHA256(h_(i-1) || payload_i)`),
one record per line as `prevHash \t hash \t base64(payload)`. `verify()` recomputes
the chain and returns the first broken index; retroactive edits, deletions, and
reordering are all caught. Dependency-free, deterministic, byte-reproducible.
This closes the "determinism isn't a benefit / you have nothing unique" surface:
it is the local, offline, per-verdict evidence record that no ASF tool provides
and an LLM tool structurally cannot be. 11 tests, 100% line coverage (59/59).
Not yet wired into the CLI run path (that is the next step); the cryptographic
core is real and tested now.

**Fix B - re-scope the `asf` preset so AIV stops competing where it loses.**
The preset previously enabled syntax, dependency, security, design, density,
cohesion - every one of which has a better-maintained, Python-native incumbent
(gitleaks, import-linter, ruff, Semgrep, SonarCloud, standard pre-commit, RAT).
That was pure attack surface ("your X is worse than <incumbent>"). The `asf`
preset is now deliberately narrow: `license-header`, `pr-metadata`, `invariant`,
all advisory, with an in-file comment pointing projects to the incumbents for
everything else. You cannot attack a claim that is no longer made.

**Fix C - delivery as a prek hook (already true).**
AIV already ships a pre-commit/prek hook (`aiv-gate` in `.pre-commit-hooks.yaml`),
so the "rival pipeline to prek/Magpie" surface was already closed: AIV runs
inside the prek a project already has, not beside it. Documented, no code needed.

Net scoping decision recorded: **AIV's ASF pitch is the evidence layer only**
(deterministic provenance/disclosure checks + tamper-evident audit, advisory,
delivered inside prek, under Magpie's judgment layer). It makes no code-quality,
CI-cost, or feature-parity claims - those all lose to incumbents and are dropped.

Next to fully close the surface: wire `aiv-audit` into the CLI run (write a
verdict record per run, add a `verify-audit` path), ship Python-first parity, and
run an independent zero-FP calibration.

### 10. Reposition: orchestrate, do not replace (+ orchestration/report spine)

Strategic change so AIV never triggers a "we already have X" pushback: AIV
replaces no Apache tool. Per concern it (1) delegates to the existing tool if the
project runs one, (2) falls back to an AIV built-in gate only where no tool
exists, (3) skips otherwise - and it produces one governance verdict, one
tamper-evident audit, and one report across all providers. "We already have
gitleaks/ruff/RAT/Magpie" becomes a reason to adopt AIV (it orchestrates them),
not a reason to reject it. Documented as Section 0 of the analysis doc.

New module `aiv-governance` implementing the deterministic spine of that model:
- `ConcernOutcome` (+ `Mode` DELEGATED/BUILTIN/SKIPPED, `Status` PASS/WARN/FAIL):
  the normalized unit, identical whether gitleaks, RAT, Magpie, or AIV produced
  it.
- `ConcernRouter.resolveMode(hasDelegate, hasBuiltin)`: the "prefer existing,
  fall back to AIV, else skip" policy as a pure, auditable function.
- `GovernanceReport`: `overall()`, `render()` (unified Markdown report across all
  tools), `canonical()` (deterministic audit payload).
- `GovernanceAudit.record()`: commits the canonical result to the hash-chained
  audit (`aiv-audit`), turning a set of per-tool outcomes into a permanent,
  verifiable record in one call.
10 tests, 100% line coverage across all classes and enums.

Honest scope: this is the orchestration *decision* + *report* + *record* spine,
fully tested with no external dependency. The external-tool execution adapters
(shelling out to gitleaks/ruff/RAT and parsing their output) are the next
increment - deliberately not built in a 100%-coverage module because a real
process-runner cannot be fully covered without executing those tools; that
adapter belongs in the CLI/integration layer with its own test seams.

Net model now recorded end to end: AIV = orchestration + governance + tamper-
evident audit + reports for Apache, composing existing tools and filling gaps,
never replacing.

### 11. Second red-team pass on the orchestration/audit model + fixes

Ran the devil's-advocate again, this time against the new "orchestrate + record"
positioning (Rounds 15-22 in `RED-TEAM-OBJECTIONS.md`). It found one real,
product-level hole and several overclaims. Fixes applied rather than argued:

**Real hole (Round 17): in-repo tamper-evidence is theatre.** A committer with
write access can rewrite a record AND recompute the whole chain, so a
self-contained in-repo chain only catches naive/accidental edits.
- **FIX (code):** added `HashChainAudit.verify(log, expectedHead)` - anchored
  verification. The head hash is pinned to an external anchor (CI job log, signed
  tag, or transparency log); a rewritten-and-recomputed chain still fails because
  its head will not match the anchor. New `VerifyResult.headMismatch(...)` +
  `isHeadMismatch()`. aiv-audit now 15 tests, 100% line coverage (57/57 + 12/12).
- **FIX (doc):** the audit threat model is now stated explicitly in
  `RED-TEAM-OBJECTIONS.md` - what is defended with/without an anchor, and what is
  out of scope. Until anchored, it is called an integrity check, not a compliance
  record.

**Overclaims corrected (doc/scope, not code):**
- Magpie is NOT a delegate target (it is an interactive Claude Code skill, not
  CI-callable). Reclassified to "coexists with / feeds"; AIV is the deterministic
  layer under it, never invokes it.
- "Orchestration" must be scoped to *normalize + record*, not "runs your tools"
  (CI already runs them). The unified report is positioned as the tamper-evident
  evidence rollup, not a dashboard (GitHub Checks/SARIF already do dashboards);
  AIV emits SARIF for the dashboard need.
- Determinism is a property of AIV's own model-free verdict path only; delegated
  tool outputs are recorded verbatim with tool identity+version (`name@version`),
  and reproducibility is bounded by that version.
- Orchestration is aspirational until >=1 real delegate adapter exists; today the
  built piece is the decision + record spine, and that is all that is claimed.

**Revised terminal (recorded):** the built, genuinely-unique nugget is small - a
dependency-free deterministic verifier for the string-fact provenance concerns
plus an anchored hash-chained record. The governance-platform story is either
redundant with CI/Checks (claim only normalization+recording) or aspirational
until adapters exist. Sold as the narrow nugget it survives; sold as a platform
it gets killed on scope, the in-repo audit, and the missing adapters.

### 12. Third red-team pass - blunt value verdict

Ran the devil's advocate a third time, aimed at the surviving narrow nugget, to
answer "value or useless?" (Rounds 23-28 in `RED-TEAM-OBJECTIONS.md`). No fixes
faked; the finding is about scope/demand, not a code bug. Honest verdict:

- For the median Apache project today: **effectively useless** - redundant with
  git + forge (signed commits, protected branches, trailers), checks are
  fakeable-presence or weekend-script-sized, the external anchor that would make
  the audit meaningful is not built, and Sigstore/Rekor/SLSA already own
  transparency.
- Non-useless only in a narrow niche: a project under an active AI-provenance
  obligation that squash-merges and wants one anchored, cross-tool merge-time
  evidence record - and only once anchoring + >=1 adapter exist.
- Never valuable as a standalone project. Honest destiny: a small component that
  writes provenance into the squash/merge record git already anchors and anchors
  its rollup to Sigstore/Rekor, rather than being a parallel log.

Recorded so the value question is not re-litigated from scratch: the code built
this session is correct and green, but its real-world value is small and
conditional, and "stop here" is a legitimate decision if the narrow niche is not
the goal.