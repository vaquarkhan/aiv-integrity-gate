# AIV adversarial product review

This ledger records the product-owner devil's-advocate process used to attack AIV's public claims, trust boundaries, integrations, gates, evidence, workflows, and documentation. A finding was closed only by code/test/document changes and independent re-review.

“Zero” means zero unresolved **Critical** and **High** product/trust findings. It does not mean every heuristic is perfect or that market adoption is proven.

## Severity

- **Critical:** advertised/default path is materially wrong or bypassable.
- **High:** realistic trust, correctness, supply-chain, or integration defect.
- **Medium:** bounded heuristic/documentation/diagnosis/adoption limitation.
- **Low:** polish/convenience.

## Initial attack surface

| Area | Initial attack |
|------|----------------|
| release/action | v2 story but stable Action/Central still 1.0.4; no-op inputs |
| policy | config pinned but sidecars/baselines could come from branch/live workspace |
| verify | request hash did not bind resolved policy; unknown options accepted |
| JSON | regex memory parser and YAML-compatible JSON parsing |
| AI-DLC | blocking integration failed open; file-only input overstated cross-file evidence |
| audit | concurrent writers could fork the hash chain |
| publication | label/comment selection could use untrusted workspace config |
| SARIF | advisory findings emitted as errors; unverified pass findings dropped |
| syntax tools | missing/crashed/hung external validators looked like valid syntax |
| workflows | CI timeout bypass, mutable action tags, release-source/tag mismatch |
| claims | unsupported speed/precision/offline/model-free absolutes |

Baseline: **Critical 3, High 10, Medium 7**. Gate outcome: rework.

## Hardening loop 1: strict boundaries and integration

- Replaced regex in-memory diff parsing with strict shared JSON and schema validation.
- Unknown `changeType` now fails closed.
- Added schema-v2 `aiv verify`, clean stdout JSON, policy/evidence fields, external-input disclosure, and positive capability handshake.
- Added strict unknown/missing option rejection for normal/verify commands.
- Added `--require-trusted-config` and `--require-trusted-policy`.
- Repaired every composite Action input and added JSON/SARIF outputs plus local/URL/version jar resolution.
- Made CI timeout fail closed.
- Added advisory and fail-closed AI-DLC sensors, full-request mode, plugin manifest/tree, and adapter tests.
- Serialized audit append with JVM + OS file lock; added thread and separate-process tests.
- Preserved hard/advisory SARIF severity.
- Reused trusted publication config for labels/comments.

Re-review found additional trust details; gate remained rework.

## Hardening loop 2: complete policy tree

- Compared the complete live `.aiv` tree with the trusted Git tree.
- Included ignored/untracked extra files in comparison.
- Rejected symbolic-link/type/path escape policy entries.
- Required configured custom design/rule files to exist; malformed policy fails.
- Bound resolved policy commit and exact sidecar bytes into verify evidence.
- Pinned all third-party GitHub Actions to full commit SHAs.
- Checked out/validated the requested tag commit before Maven Central publication.
- Added strict syntax-tool timeout, process-tree termination, capped diagnostics, and visible `syntax.unverified`.
- Added `strict_toolchains` fail-closed policy.

Re-review found strict JSON lexical and time-of-check/use weaknesses; gate remained rework.

## Hardening loop 3: strict RFC JSON

`JsonLite` now enforces RFC 8259 lexical behavior:

- only space/tab/LF/CR whitespace,
- no raw control characters in strings,
- valid four-hex Unicode escapes,
- no leading plus/zero number forms,
- required digits after decimal/exponent,
- ASCII number digits only,
- no trailing input.

`SyntaxGate`, `MemoryDiffProvider`, and `VerifyCommand` all use this parser. Tests cover YAML-only JSON, comments, tags, trailing commas, raw controls, invalid number forms, non-JSON whitespace, and Unicode escapes.

## Hardening loop 4: immutable policy consumption

Checking policy before use was not enough because a concurrent process could change it afterward. AIV now:

1. resolves a base ref once to a commit OID,
2. reads base config through that OID,
3. lists and reads policy blobs through that OID,
4. materializes `.aiv` into a private snapshot,
5. passes a separate `policyWorkspace` through `AIVContext`,
6. makes design, generic rules, documentation rules, and baselines consume that snapshot,
7. hashes the same snapshot into evidence,
8. deletes it after evaluation.

Workspace/diff-json runs copy `.aiv` once for internal consistency. Explicit CLI baselines are copied into the snapshot.

## Hardening loop 5: single commit through audit

A symbolic ref could move during a run. `GitRefConfigProvider` now caches the first resolved commit and reuses it for config, tree comparison, snapshot listing, blob reads, and verify evidence. `Main` captures that pinned OID and passes it into the audit record as both `base_sha` and `policy_commit`; audit does not re-resolve the symbolic ref.

A regression moves symbolic `HEAD` after policy resolution and proves the snapshot/audit remain tied to the original commit.

## Gate-by-gate strength target

| Gate | Product-strength boundary |
|------|---------------------------|
| syntax | strict named parser; unavailable parser visible/optionally blocking |
| invariant | stable objective added-line markers; conflict whole-file |
| dependency | deterministic local declaration evidence; config for nonstandard layouts |
| package-existence | found/absent/unverified; registry dependency declared |
| test-adequacy | assertion-presence evidence; missing-test advisory |
| security | high-confidence added-line tripwire; not full history scanner |
| design | immutable repository-owned sidecar |
| rules | immutable generic YAML packs; missing/malformed fails |
| density/cohesion/duplication | advisory review-focus signals |
| external | trusted configured command, bounded process, SARIF severity preserved |
| doc-integrity | source targets + frozen policy rules |
| pr-metadata/license | opt-in structural policy |
| jev | model-backed external input, always advisory |

## Integration and evidence strength target

- Same gate result across stdout, JSON, SARIF, GitHub, audit.
- One pinned policy commit per run.
- Exact request/config/sidecar/commit evidence digest.
- Strict mode fails on fallback/drift/symlink/ignored extras.
- AI-DLC blocking requires trusted policy and rejects external inputs.
- Hash-chain append serialized across threads/processes.
- External head supported for complete-rewrite detection.
- Action requires a positive capability handshake.

## Final validation

- Fresh full `mvn clean verify`: **BUILD SUCCESS**, all 25 reactor projects and configured coverage gates.
- Surefire inventory at final product pass: **844 tests, 0 failures, 0 errors, 1 OS capability-gated symbolic-link test skipped**.
- AI-DLC adapter: **8 tests passed**.
- Documentation: **24 detailed module READMEs plus local links validated**.
- Visuals: generated PNG/SVG assets validated.
- Sales deck: rendered natively by PowerPoint and structurally validated.
- Independent final trust review after pinned audit metadata: **Critical 0, High 0**.

## Final verdict

**READY for a controlled product pilot.**

**Unresolved Critical: 0. Unresolved High: 0. DONE.**

Medium backlog remains visible by design: heuristic limits, field/adoption evidence, precise doc finding locations, same-change duplication, and external-customer validation. None is represented as a product guarantee.

**Author:** Viquar Khan

## Publication-quality documentation refresh validation

After the all-gates Hello World, module adoption guide, eight-figure architecture rewrite, InfoQ-style article, explicit presets, documentation-policy snapshot fix, and revised sales deck:

- Full `mvn clean verify`: **BUILD SUCCESS**, all 25 reactor projects and coverage gates, 844 tests, 0 failures, 0 errors, 1 OS capability-gated symbolic-link skip.
- `aiv-adapter-git`: 42 tests passed after stabilizing the suite-wide Windows timeout; dedicated one-second timeout tests remain.
- AI-DLC adapter: 8 tests passed.
- Five preset source/package copies: byte-identical.
- YAML/frontmatter: 13 files parsed.
- Documentation validator: 24 module READMEs and all local links passed.
- Visual validator: 8 generated current architecture SVG/PNG pairs plus retained PNG assets passed.
- Sales deck: 21 slides, native PowerPoint render, requested opening-six order, required topics, bounds, metadata, and punctuation checks passed.

**Final status remains: Critical 0, High 0, DONE.**
