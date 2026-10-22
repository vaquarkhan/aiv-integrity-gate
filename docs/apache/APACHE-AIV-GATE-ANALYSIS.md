# Apache AIV Gate

An end-to-end analysis of the governance problems that every Apache project
shares, and a design for solving the deterministic slice of them once, so no
project has to rebuild the same fix.

Status: research and design, with a first implementation increment delivered
locally (see Section 8 and `PRODUCT-CHANGES.md`). Read-only investigation against
GitHub; nothing committed or pushed.

---

## 0. Positioning: orchestrate, do not replace (read this first)

AIV replaces no Apache project and no existing tool. That framing loses every
"we already have X" vote and it is the wrong design anyway. AIV is a
**governance orchestrator and system-of-record**: it composes the tools a
project already runs, fills a gap only where nothing exists, and produces one
verdict, one tamper-evident audit, and one report across all of them.

Per governance concern, AIV resolves a provider in three tiers:

1. **Delegate (preferred).** If the project already runs a good tool for the
   concern (gitleaks, ruff, Semgrep, import-linter, Dependabot, Apache RAT,
   Magpie), AIV hooks into it - invokes it or ingests its output - and
   normalizes the result. AIV adds nothing to the check itself.
2. **Fallback.** If no tool covers the concern, AIV uses its own built-in gate.
3. **Skip.** If the concern does not apply, no-op (precision-first).

AIV's own value is never the individual check; it is the layer above: the
deterministic orchestration, the end-to-end **tamper-evident audit**, and the
unified **governance report**. It is the conductor and the record, not a chair
in the orchestra.

### Default concern -> provider map for an Apache project

| Governance concern | Preferred provider (delegate) | AIV's role |
|--------------------|-------------------------------|------------|
| Secrets in diff | gitleaks / GitHub secret scanning | orchestrate + record |
| Import / dependency boundaries | import-linter / ruff / Dependabot | orchestrate + record |
| Lint / complexity | ruff / Semgrep / SonarCloud | orchestrate + record |
| License headers (release) | Apache RAT / ATR | orchestrate + record; AIV builtin adds a PR-time check |
| Merge markers / broken artifacts | standard pre-commit / CI | orchestrate + record |
| PR triage / semantic judgment | Apache Magpie | orchestrate + record (AIV is the deterministic layer under it) |
| AI provenance / disclosure / attribution | (no incumbent) | **AIV builtin** |
| Tamper-evident audit + unified report | (no incumbent) | **AIV builtin - the spine** |

### How this converts the killer objections into +1s

- "We already have gitleaks / ruff / RAT / Magpie." -> Good. AIV calls them and
  rolls their results into one governance verdict, one tamper-evident audit, and
  one report. You keep every tool; you gain the system-of-record. The existing
  tool is now a *reason to adopt* AIV (it orchestrates it), not a reason to
  reject it.
- "We don't want two solutions for one problem." -> There is one solution per
  concern - the one you already chose. AIV adds zero new checkers where you have
  them; it is the governance layer above, not a second checker.
- "It's redundant." -> It is redundant with nothing, because in delegate mode it
  runs your tool, not its own. Its only original checks are where you have no
  tool (AI provenance) and the audit/report (which nothing else produces).

The rest of this document keeps the earlier deterministic-verifier analysis; read
it through this orchestration lens - the deterministic checks are the *fallback
tier*, and the audit/report is the spine.

---

## 1. The meta-problem (why this is a foundation problem, not a project problem)

Every ASF project must obey the same foundation-wide rules: Apache license
header on every source file, third-party dependencies confined to allowed
license categories, correct LICENSE/NOTICE, provenance and attribution for
AI-assisted contributions, no secrets in the tree, no broken artifacts, and a
signed, voted release. These rules do not change from Kafka to Spark to Beam to
Airflow.

The enforcement of those rules, however, is fragmented and mostly happens at the
wrong time:

- **Release-time, whole-artifact:** Apache RAT (headers), Whisker (LICENSE
  generation), and ATR at releases.apache.org (signatures, hashes, archive
  layout, LICENSE/NOTICE, RAT, SBOM/vuln) all run against the release tarball,
  after code has been merged for weeks or months. RAT itself states it "will
  produce false positives" and "reports require interpretation."
- **Maintainer-side, LLM judgment:** Apache Magpie (and airflow-steward) now run
  PR triage and review as Claude Code skills, maintainer-in-the-loop. Good at
  judgment, costs tokens, needs a human to confirm every action.
- **Per-project, ad hoc:** each project wires its own pre-commit hooks, CI jobs,
  and RAT config. The same classes of defect are re-litigated project by
  project.

There is a hole in the middle: **no uniform, deterministic, shift-left gate at
the contribution boundary (commit and PR).** That hole is exactly where
AI-generated contributions now arrive at machine speed. This is the AIV niche,
and it is the same for every project, so it can be solved once.

```
Contributor / agent            Maintainer                Release manager
   commit + PR                    review                     vote
------------------------------------------------------------------------
[  AIV Gate  ]  --->  [  Magpie (LLM)  ]  --->  [  ATR / RAT / Whisker  ]
 deterministic          judgment                 release-artifact audit
 diff-scoped            semantic                 whole-tarball
 shift-left, no token   token cost               release-time
```

AIV's job is the first box only, done once for the whole foundation, feeding
the next two boxes so they do less work.

---

## 2. Catalog of common ASF problems, with decidability

Each row: the rule, whether it is deterministic, where (if anywhere) it is
tooled today, and the owner it should have.

| # | Common ASF rule | Deterministic? | Tooled today | Right owner |
|---|-----------------|----------------|--------------|-------------|
| 1 | Apache license header on new source files (src-headers policy) | Yes | RAT / ATR at release time, whole tree | **AIV** at PR time, diff-scoped |
| 2 | Third-party dep in allowed license category (A / B / X) | Mostly | Manual IP review; ATR SBOM at release | **AIV** dependency gate + Category-X denylist |
| 3 | LICENSE/NOTICE updated when a dep is bundled | Partly | ATR / Whisker at release | **AIV** advisory at PR; ATR authoritative |
| 4 | AI provenance + attribution trailers (`Assisted-by`/`Generated-by`), Gen-AI disclosure | Yes (presence/format) | Nothing deterministic | **AIV** provenance gate |
| 5 | Never detach a license/copyright notice from moved code (agent guidance) | Partly | Nothing | **AIV** advisory + RAT backstop |
| 6 | No secrets/credentials in the diff | Yes | Per-project scanners | **AIV** security gate |
| 7 | No malicious CI/workflow tampering ("suspicious changes") | Partly | Magpie flag-suspicious (LLM+human) | **AIV** advisory label + human |
| 8 | No broken artifacts: parse errors, merge markers, agent edit-paste, placeholder tests, hallucinated imports | Yes | Per-project CI | **AIV** syntax/invariant/dependency |
| 9 | PR hygiene: descriptive title, meaningful body, no unrelated bundling | Title/body/prefix yes; "meaningful"/"coherent" no | Magpie (LLM) | **AIV** for the string-decidable part; Magpie for judgment |
| 10 | DCO / sign-off + commit identity hygiene | Presence yes; ICLA representation no | Per-project DCO bot | **AIV** trailer presence; human for the representation |
| 11 | Release integrity: signatures, hashes, archive layout, SBOM | Yes | **ATR (complete)** | **ATR**: AIV must not duplicate |
| 12 | Voting: 3 binding +1 PMC votes, 72h, lazy consensus | No | Human governance | Human: not automatable |

### The three buckets

1. **Deterministic, diff-scoped, contributor-time (rows 1, 2, 4, 6, 8, and the
   hard part of 9/10):** this is the Apache AIV Gate. Solve once.
2. **Judgment / semantic (rows 5, 7, and the fuzzy part of 9):** Magpie / LLM.
   AIV surfaces advisory signals and hands off; it never hard-blocks here.
3. **Release-artifact audit (rows 3, 11):** ATR / RAT / Whisker. AIV
   *pre-satisfies* these by catching header/license issues at PR time, but never
   replaces them.

Pure human governance (row 12, ICLA representations in row 10, editorial
responsibility under the EU AI Act) is out of scope for any tool.

---

## 3. What the ASF AI policy actually asks for (and what a tool can do about it)

The ASF Generative Tooling Guidance and the draft "AI Agents and AGENTS.md
Files" are explicit that the core conditions are **human judgments**, not scans:

- whether the tool's terms grant rights consistent with the Open Source
  Definition,
- whether the output is copyrightable subject matter,
- whether third-party material is present and properly licensed.

The draft says plainly that an agent writing "no third party material" into a
commit "has not checked anything; it has produced a record that looks considered
and is not." So AIV must never assert these. What the same draft says a tool
*can* usefully do maps directly onto deterministic checks AIV can own:

- **Record what was used.** Commit attribution trailers:
  `Assisted-by: <agent> (<model-id>)` when a person decided the substance,
  `Generated-by: <agent> (<model-id>)` when the agent originated it. Presence and
  format are deterministically checkable.
- **Keep notices attached.** Detect a moved/reformatted file that lost the
  license header its source had. Deterministic as an added-file header check;
  advisory when it looks like a relocation.
- **Disclose Gen-AI use.** Presence of the disclosure block in the PR body when
  the project requires it. Presence is checkable; "was AI actually used" is not,
  so AIV only asserts presence, never absence-of-disclosure-means-lying.

This is the honest line: AIV enforces the *mechanical, evidentiary* half of the
AI policy (attribution, disclosure presence, notice attachment) and leaves the
*legal judgment* half to the contributor and PMC.

---

## 4. Apache AIV Gate: the design

### 4.1 Naming and status (read this first)

AIV today is `io.github.vaquarkhan`, and its own README states it is **not an
ASF project**. "Apache AIV Gate" is therefore an *aspiration*, not a current
fact. To genuinely be foundation-wide it needs one of:

- donation to the ASF (incubator) so it can carry the Apache brand, or
- adoption as blessed third-party tooling the way projects opt into other
  external actions, under a neutral name (for example "ASF-aligned AIV preset").

Until then the correct framing is: **a deterministic, ASF-aligned gate that any
Apache project can adopt**, and a candidate for donation. I would not ship the
"Apache" name without that conversation with the ASF, because trademark and
provenance are themselves governance rules here.

### 4.2 The foundation-common gate set

Reuse what AIV already has; add three gates and extend one. All precision-first
(when uncertain, pass), all diff-scoped.

| Gate | New/Existing | Hard or advisory | What it enforces |
|------|--------------|------------------|------------------|
| `syntax` | existing | hard | Added files parse |
| `invariant` | existing | hard | Merge markers, agent edit-paste, placeholder tests |
| `dependency` | extend | hard (X) / advisory (B) | Imports declared; **+ Category-X license denylist**, Category-B needs-NOTICE advisory |
| `license-header` | **new** | hard | Apache header on added source files (RAT rules, diff-scoped) |
| `provenance` | **new** | hard/advisory | `Assisted-by`/`Generated-by` trailer format; Gen-AI disclosure presence |
| `pr-metadata` | **new** | hard | Title/body hygiene: generic-title denylist, conventional-commit prefix, empty/template-only body |
| `security` | existing | hard | Secrets/credentials in the diff |
| `notice-attachment` | extend design/invariant | advisory | Moved code that dropped its header |
| `density` / `cohesion` | existing | advisory + label | Sprawl, multi-area bundling (never a sole hard block) |

Two data files ship inside the CLI JAR and encode the foundation-common facts so
projects do not re-key them:

- the **Category A/B/X** license lists from the ASF resolved policy,
- the **ASF license-header** matchers (kept in step with RAT's).

### 4.3 The `asf` preset (one-command adoption)

`java -jar aiv-cli.jar init --preset asf` writes a config with the gate set
above, the correct hard/advisory split, and design rules pre-loaded with the ASF
Gen-AI attribution markers. Language-neutral, so it works for Python-heavy
Airflow and JVM-heavy Kafka/Spark alike. This is the same preset mechanism AIV
already uses for `agent-paste-strict`, `java-ci`, and `minimal`.

### 4.4 Per-project overrides (the small, enumerable difference)

One file, `.aiv/asf-overrides.yaml`, is all a new project fills in. The pattern
mirrors Magpie's `.apache-magpie-overrides/`:

```yaml
pr_template_path: .github/PULL_REQUEST_TEMPLATE.md
trusted_authors_source: .asf.yaml        # committers get the lazy-consensus lane
area_label_prefix: "area:"
languages: [python]                       # header/dependency scope
rat_excludes_url: https://raw.githubusercontent.com/apache/<proj>/main/.rat-excludes
```

Note the last line: AIV reads the **same `.rat-excludes`** file RAT/ATR already
use (ATR accepts exactly this URL form on apache.org or raw.githubusercontent),
so exclusions are maintained once, not twice.

### 4.5 Fit with the rest of the ecosystem (no duplication)

- **Feeds Magpie:** AIV's `--output-json` verdict lets the Magpie triage skill
  skip re-judging the mechanical criteria and spend tokens only on judgment.
- **Pre-satisfies ATR:** header and license-category problems get caught at PR
  time, so they do not surface as release blockers weeks later.
- **Closes the agent loop:** ship an `AGENTS.md` snippet (per the ASF draft)
  telling agents the gate will check their attribution trailers and notices, so
  well-behaved agents self-comply before the gate even runs.

### 4.6 Distribution for uniform rollout

- A **reusable GitHub workflow / composite action** defaulting to `--preset asf`,
  referenced in one YAML block. ASF projects share org tooling and `.asf.yaml`,
  so a single infra-blessed reusable workflow gives uniform coverage.
- The **pre-commit hook** for the contributor-side, shift-left path, matching
  Airflow's own `prek` model (same checks as CI, only on changed files, less CI
  burden).

---

## 5. Calibration: zero false positives is the adoption gate

A preset dropped into hundreds of projects unattended is only safe if it almost
never blocks a good PR. This is the one non-negotiable.

- Every hard rule stays precision-first: fire only on objective, added-line
  defects; skip when the toolchain is missing or the file is intentionally
  non-strict.
- Before any rule ships hard in the `asf` preset, validate it against that
  repo's own **merged-PR history** (git is the labeled corpus: merged = "good",
  closed-for-quality = candidate "bad"). A rule that fires on any merged PR is
  disqualified from the hard path.
- This is AIV's concrete improvement over RAT, which by its own admission
  produces false positives and needs interpretation: AIV is diff-scoped and
  precision-first, so it only speaks about the lines this PR added.

Prior benchmarking already showed objective hard markers are rare (about 0.17%)
in real Airflow closed PRs, and that diff-shape cannot separate slop from
legitimate work. Both findings are load-bearing here: they set what may be hard
(headers, category-X, attribution format, broken artifacts, title/body strings)
and what must stay advisory forever (density, cohesion, "meaningful",
"coherent").

---

## 6. Honest limits

- AIV covers **bucket 1 only**. The dominant "AI slop" (plausible-but-low-value
  code) is bucket 2 and belongs to Magpie/LLM review. Do not claim AIV "solves
  AI slop for the ASF."
- Copyrightability, tool-terms acceptability, and which of the guidance's three
  conditions a contribution relies on are **human judgments** (the ASF draft says
  so explicitly). AIV records and attributes; it never asserts them.
- Release integrity is **ATR's** job. AIV shifts some of it left but does not
  replace it.
- Votes, ICLA representations, and EU AI Act editorial responsibility are
  **human governance**.
- The "Apache" name requires actual ASF adoption or donation.

---

## 7. What to build first (suggested order)

1. `pr-metadata` gate (title/body hygiene) and `provenance` gate (attribution
   trailers + disclosure presence). Highest leverage, purely string-decidable,
   nothing else in the ecosystem does them deterministically.
2. `license-header` gate (diff-scoped ASF header on added files) reusing RAT's
   matchers and `.rat-excludes`.
3. `dependency` gate extension with the Category A/B/X data file.
4. The `asf` preset + `.aiv/asf-overrides.yaml` contract + reusable workflow.
5. Calibration harness run against 5-10 diverse ASF repos' merged-PR history to
   prove zero false positives before recommending the hard path.

All of this can be prototyped locally for review with nothing committed or
pushed.

---

## 8. Implementation status (delivered locally, verified green)

This increment turned the top of Section 7 into working, test-covered code. See
`docs/apache/PRODUCT-CHANGES.md` for the file-by-file log.

| Item | Status | Notes |
|------|--------|-------|
| `pr-metadata` gate (`aiv-plugin-prmeta`) | Done | Title/body hygiene + Gen-AI disclosure presence. 18 tests, 100% line coverage. Opt-in (default-off; enabled by the `asf` preset). |
| Provenance | Done (split) | Disclosure-presence lives in `pr-metadata`; AI attribution trailers pasted into source are already caught by the existing `invariant` gate. |
| `asf` preset | Done | `aiv init --preset asf`. Hard broken-artifact gates; `pr-metadata`, `license-header`, density, cohesion advisory (`severity: warn`). |
| `license-header` gate (`aiv-plugin-license-header`) | Done | Diff-scoped Apache header on newly added source files, RAT-aligned. 13 tests, 100% line coverage. Opt-in. |
| Orchestrator default-off set | Done | `license-header` and `pr-metadata` join `doc-integrity`/`security` as opt-in, so adding the modules never changes behavior for projects that have not adopted the `asf` preset. |
| `dependency` Category A/B/X | Deferred | A robust check needs dependency-license resolution (ATR/SBOM territory) or a curated coordinate denylist that must be calibrated. Shipping a thin denylist now would risk false positives, against the precision-first rule. Tracked as the next increment. |
| `.aiv/asf-overrides.yaml` contract + reusable workflow + `.rat-excludes` reuse | Deferred | Design is in Section 4; not yet wired. |
| Hash-chained tamper-evident verdict audit + accountable HITL override | Deferred (vetted idea) | From the sibling MCP-Bastion work: a SHA-256 hash chain over verdict/override records that detects retroactive edits, plus an override record binding identity and reason. Strong fit for ASF auditability, EU AI Act Art. 14, and NIST AI RMF. |
| `podling` gate (incubating naming, DISCLAIMER, header checks at PR time) | Deferred (vetted idea) | ASF-specific: verify `incubating` in artifact names and presence of DISCLAIMER/headers at the PR, complementing RAT/ATR at release. |

External evidence added to the motivation: the sibling MCP-Bastion study measured
a **25% advisory-only violation rate** across six frontier models (~26.7% even
with an emphatic system prompt), independent support for the claim that
rule-guided agents still emit violations and a deterministic gate is needed.

Verification: full `mvn clean verify` across the reactor, 100% line coverage
enforced per module. Build command and gotchas are recorded in
`PRODUCT-CHANGES.md`.

### Key design decision recorded here

New ASF gates are **opt-in**. Discovery via ServiceLoader puts them on the
classpath, but the orchestrator leaves them off unless a project enables them
(directly or through the `asf` preset). This preserves the precision-first
promise: adopting an Apache-specific check is a deliberate act, and existing AIV
users see no behavior change.
