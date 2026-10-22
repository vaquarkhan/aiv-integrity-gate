# AIV Gate for Apache Projects: Adoption Roadmap

A plan for how Apache projects adopt a deterministic, shift-left integrity gate,
why they would want to, and what they get out of it at each step.

Status: proposal and roadmap. AIV is currently `io.github.vaquarkhan` and is not
an ASF project. This document treats foundation adoption as a goal to work
toward, and Section 8 covers the naming and donation question honestly.

---

## 1. In one line

Give every Apache project the same deterministic, precision-first check at the
commit and pull-request boundary, so objectively broken and non-compliant
changes are caught in seconds, before they reach a maintainer's review or a
release audit, and so no project has to build that check itself.

---

## 2. Why Apache projects need this

Three forces are squeezing maintainers at once:

- **Volume.** AI agents produce a plausible PR in minutes at near-zero cost. The
  cost lands on maintainers, who are a finite, volunteer resource. Large
  projects already carry hundreds of open PRs.
- **Fragmentation.** The same foundation-wide rules (Apache license headers,
  third-party license categories, LICENSE/NOTICE, provenance, no secrets, no
  broken artifacts) are enforced differently in every project, with a patchwork
  of pre-commit configs and CI jobs.
- **Late detection.** The strong existing tools run at release time on the
  finished tarball (Apache RAT, Whisker, ATR) or maintainer-side with an LLM
  (Apache Magpie). Nothing runs deterministically at the moment a contribution
  arrives, which is when it is cheapest to fix.

AIV Gate fills that gap once, the same way for everyone.

---

## 3. What it is (and what it is not)

It is a shaded Java CLI with no LLM and no API keys on the blocking path. It
reads the diff and the PR metadata, runs a set of deterministic gates, and
returns pass or fail. It is precision-first: when it is not certain, it passes.

- It **is** the first, deterministic stage of PR handling.
- It is **not** a replacement for human review, for Magpie's LLM judgment, or for
  the ATR release audit. It runs in front of them and makes their job smaller.

```
Contributor / agent          Maintainer               Release manager
   commit + PR                  review                     vote
--------------------------------------------------------------------
[  AIV Gate  ]  -->  [  Magpie (LLM)  ]  -->  [  ATR / RAT / Whisker  ]
 seconds, no token     judgment, tokens         release-time audit
```

---

## 4. Benefits by stakeholder

| Stakeholder | What they get |
|-------------|---------------|
| **Contributors** | Fast, local feedback from the pre-commit hook. They fix a bad header, a broken file, or a vague title on their own machine instead of waiting days for a maintainer to point it out. |
| **Maintainers / committers** | The mechanical first pass is done before they open the PR. Their attention goes to design and correctness, not to checking headers, parse errors, and title quality by hand. |
| **PMC** | Uniform, auditable enforcement of foundation rules across the project, with a written config in the repo. Less risk of a license or provenance problem being discovered late. |
| **Release managers** | Header and license-category problems are caught at PR time, so they stop surfacing as release blockers weeks later in the ATR/RAT audit. |
| **ASF (foundation)** | One shared, calibrated gate instead of N project-specific reinventions. Consistent handling of the new AI provenance and attribution expectations. |
| **Well-behaved AI agents** | An AGENTS.md tells them the rules the gate enforces (attribution trailers, keep notices attached, disclose AI use), so they self-comply before the gate runs. |

---

## 5. The roadmap

Five phases. Each phase has a goal, what ships, and a clear exit criterion so a
PMC can see when it is safe to move on. The non-negotiable running through all of
them is zero false positives: a gate that blocks good PRs will be turned off and
never trusted again.

### Phase 0: Foundations (deterministic core proven)

- **Goal:** prove the deterministic gates are safe and useful on one real project.
- **Ships:** the existing gates (syntax, invariant, dependency, security,
  density/cohesion advisory), plus the calibration harness that scores a gate
  against a repo's merged-PR history.
- **Exit:** on one large ASF repo's recent merged PRs, zero hard-fails on merged
  ("good") PRs. This is the trust floor.

### Phase 1: The compliance gates (the Apache-specific value)

- **Goal:** cover the foundation rules nothing else checks deterministically at
  PR time.
- **Ships:**
  - `pr-metadata` gate: descriptive title, no conventional-commit prefix, no
    empty or template-only body. **[delivered]**
  - `provenance`: Gen-AI disclosure presence (in `pr-metadata`) plus AI
    attribution trailers pasted into source (already in the `invariant` gate),
    aligned to the ASF generative-tooling guidance. **[delivered]**
  - `license-header` gate: Apache header on added source files, diff-scoped,
    RAT-aligned. **[delivered]** (reusing the project's `.rat-excludes` is a
    follow-up.)
  - `dependency` extension: Category A/B/X license data so a Category-X
    dependency is flagged at PR time. **[deferred]** - needs dependency-license
    resolution or a curated, calibrated coordinate denylist; overlaps ATR/SBOM.
- **Exit:** each delivered gate validated zero-false-positive against merged
  history; each maps to a written ASF rule.

- **Delivered so far (local, verified green):** `aiv-plugin-prmeta` (18 tests,
  100% line coverage), `aiv-plugin-license-header` (13 tests, 100% line
  coverage), the `asf` preset, and an orchestrator change making both new gates
  opt-in so adopting them is deliberate and existing users are unaffected. See
  `PRODUCT-CHANGES.md`. Calibration against real merged-PR history (the exit
  criterion) is still pending.

### Phase 2: The `asf` preset and one-file adoption

- **Goal:** make turning it on a five-minute job.
- **Ships:** `aiv init --preset asf` (the whole gate set with the right
  hard/advisory split), plus a single `.aiv/asf-overrides.yaml` for the few
  per-project values (PR template path, trusted-author source from `.asf.yaml`,
  area-label prefix, languages, `.rat-excludes` URL).
- **Exit:** a project adopts with one command and one small file, no custom code.

### Phase 3: Pilot on 3 to 5 volunteer projects

- **Goal:** learn from real adoption across different languages and cultures
  (for example one Python project, one JVM project, one podling).
- **Ships:** a reusable GitHub workflow (one YAML block), the pre-commit hook,
  and an AGENTS.md snippet. Each pilot starts in advisory mode (label only,
  never block), then promotes individual gates to hard only after its own
  merged-history calibration passes.
- **Exit:** pilots report reduced mechanical review load and no good-PR blocks;
  per-project calibration data is published for independent checking.

### Phase 4: Foundation-wide availability and the governance conversation

- **Goal:** make it available to any project and settle the naming/ownership.
- **Ships:** documentation for self-service adoption, and a proposal to the ASF
  about donation or blessed third-party status (Section 8).
- **Exit:** any PMC can adopt it unattended, and the "Apache" naming question has
  a clear answer.

---

## 6. How a project turns it on

1. Run `aiv init --preset asf` and fill in `.aiv/asf-overrides.yaml` (about five
   fields).
2. Add the reusable workflow (one YAML block) as a **non-blocking** CI check, and
   optionally recommend the pre-commit hook to contributors.
3. Run the calibration harness on recent merged PRs. Review which gates fire.
4. Promote a gate from advisory to hard only when it shows zero hits on merged
   PRs. Keep density and cohesion advisory permanently.
5. Add the AGENTS.md snippet so agents self-comply.

At every step the project stays in control. Nothing blocks a merge until the PMC
promotes a gate, and there is always the documented human override for hotfixes.

---

## 7. Success metrics

- **Trust:** false-positive rate on merged PRs stays at zero. This is the metric
  that decides everything else.
- **Shift-left:** share of header, parse, and metadata issues caught at
  pre-commit or PR time instead of at release audit.
- **Review relief:** reduction in maintainer comments spent on mechanical issues
  (title, header, broken file), measurable from the project's own PR history.
- **Coverage:** number of foundation rules enforced deterministically at PR time
  that previously were only checked at release or not at all.
- **Adoption:** number of projects on the `asf` preset, and how many gates each
  has promoted to hard.

---

## 8. Governance, naming, and honesty

- **The name.** AIV is `io.github.vaquarkhan` and its own README states it is not
  an ASF project. "Apache AIV Gate" is a goal, not a current fact. Foundation-wide
  status needs either donation to the ASF (incubator) or adoption as blessed
  third-party tooling under a neutral name. Trademark and provenance are
  themselves ASF governance rules, so the name should not ship ahead of that
  decision.
- **Scope honesty.** The gate covers the deterministic slice only. The larger
  problem of plausible-but-low-value AI code is a judgment problem and belongs to
  Magpie/LLM review. AIV should never be sold as "solves AI slop."
- **Legal judgment stays human.** Copyrightability, tool-terms acceptability, and
  the ICLA representations are human decisions. AIV records and attributes; it
  never asserts them. This follows the ASF's own AI-agents guidance.
- **No duplication.** Release integrity stays with ATR/RAT/Whisker. AIV reuses
  their data (`.rat-excludes`, the license category lists) rather than forking
  it.

---

## 9. Risks and how the roadmap handles them

| Risk | Mitigation |
|------|------------|
| A false positive blocks a good PR and erodes trust | Zero-FP calibration gate before any rule goes hard; advisory-first rollout; documented human override |
| Seen as competing with Magpie or ATR | Positioned and documented as the deterministic stage that feeds both |
| Projects differ too much to share one config | The `asf` preset plus a small per-project overrides file; per-project calibration |
| The "Apache" name overreaches | Neutral name until donation/adoption is agreed with the ASF |
| Agents game the checks | Deterministic checks are evidentiary, not judgment; the hard-to-fake work stays with human and LLM review |

---

## 10. Next step

Prototype Phase 1 locally (the `pr-metadata` and `provenance` gates) and run the
Phase 0 calibration against one large ASF repo's merged PRs, all read-only and
nothing committed, so the zero-false-positive claim is evidence before it is a
promise.
