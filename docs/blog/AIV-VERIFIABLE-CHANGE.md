# From Generated Code to Verifiable Change: Building a Deterministic Integrity Gate for the Agentic SDLC

**By Viquar Khan**

## Key takeaways

- AI-assisted delivery has a verification bottleneck: generation scales faster than a maintainer's ability to establish basic facts about a change.
- Compilers, SAST tools, LLM reviewers, and orchestrators each solve important problems, but none alone supplies a policy-bound, cross-tool evidence receipt for an agent-generated change.
- A useful integrity gate should block only objective, reproducible defects and keep structural or judgement-heavy signals advisory.
- The artifact and the policy that judges it must be separated. Pinning one commit and freezing every policy sidecar removes self-modifying-policy and time-of-check/time-of-use gaps.
- Machine interfaces need strict input contracts, visible uncertainty, severity-preserving output, and evidence digests tied to the exact request and policy.
- Adoption works best as a layered rollout: minimal local checks, calibrated CI evidence, strict trusted policy, then lifecycle integration.

## The problem moved downstream

Coding agents changed the economics of software creation. A developer can ask for a feature, refactor, migration, or test suite and receive a large, polished change quickly. The apparent bottleneck disappears at the editor and reappears in review.

A maintainer still has to answer ordinary engineering questions:

- Does every changed file parse?
- Is an import backed by the project manifest?
- Does a newly declared package really exist?
- Does an added test assert anything?
- Did the change paste an edit instruction or unresolved conflict into source?
- Did it introduce a credential?
- Does it violate a repository architecture rule?
- Did the change alter the policy that is supposed to judge it?

These questions are not all subjective. Many can be answered mechanically. Yet teams often spend human review time proving them, or ask another model to judge the first model's output.

LLM review can be valuable. It can explain intent, reason about design, and notice surprising interactions. But model judgement varies with context, prompt, provider, and time. It is a poor foundation for an automatic hard block unless the claimed fact can be independently reproduced.

That distinction led to AIV Integrity Gate: a deterministic evidence layer between change generation and detailed review.

## Why existing tools remain necessary, but incomplete

The goal is not to replace compilers, SAST, secret scanners, policy engines, or lifecycle orchestrators.

A compiler proves that one build configuration compiles. It may not run before the change reaches CI, and it does not explain whether a package name was hallucinated before resolution.

A SAST or secret scanner performs deep specialized analysis. It commonly scans the whole repository and produces its own report. AIV can run first on the changed surface and fold the trusted analyzer's SARIF into one result.

An LLM reviewer handles contextual judgement. AIV supplies the reproducible facts that should not depend on model judgement.

An orchestrator such as an AI-DLC workflow decides which stage runs next and when a human must intervene. AIV is the evidence-producing sensor at that boundary.

A policy engine such as OPA decides whether structured facts satisfy organizational policy. It does not compute every code fact by itself. AIV provides those facts.

This is a compositional product stance: use the best existing tool for each deep job, then create one trusted receipt at the change boundary.

## The architecture: one engine, many entry points

![AIV complete architecture](../images/aiv-architecture.png)

**Figure 1: AIV has multiple delivery surfaces but one verdict engine.** Pre-commit, CI, agents, AI-DLC sensors, and policy hosts all enter through the CLI/core. The engine runs configured gates and publishes the same severity-aware result to human and machine channels.

The Maven reactor contains one parent and 24 child modules. The important architectural split is:

- `aiv-api`: immutable models and ports.
- `aiv-core`: gate orchestration, severity, baselines, suppression, skip policy.
- `aiv-plugin-*`: 15 modules contributing 16 gates.
- Git and GitHub adapters.
- audit, report, proposal, and standalone governance modules.
- `aiv-cli`: the shaded executable composition root.

![AIV module dependency map](../images/aiv-module-map.png)

**Figure 2: The dependency direction stays one-way.** Plugins depend on API contracts rather than the CLI. The CLI bundles all runtime gate implementations through ServiceLoader. The standalone governance library remains outside the executable graph.

## A gate is an evidence function

The core Java extension contract is intentionally small:

```java
public interface QualityGate {
    String getId();
    GateResult evaluate(AIVContext context);
}
```

A gate receives a source workspace, immutable policy workspace, diff, resolved configuration, and host metadata. It returns:

- pass,
- blocking failure,
- advisory failure,
- structured findings with stable rule id, file, line/column, and message.

The core then applies configured severity, baseline suppression, and inline suppression before aggregating the result.

This makes every output surface consistent. JSON, SARIF, GitHub annotations, PR comments, audit records, and reports all consume the same `AIVResult`. No publisher reruns a check or reinterprets message text.

## Blocking facts and advisory signals

![AIV gate catalog](../images/aiv-gate-catalog.png)

**Figure 3: Sixteen gates are grouped by evidence job.** The architecture deliberately separates objective defects from review-focus signals and optional governance/model surfaces.

### Change integrity

The syntax gate proves strict parse validity where a parser is available and returns visible `syntax.unverified` otherwise. JSON is parsed with an RFC 8259 lexer rather than a YAML-compatible parser. Python, JavaScript, TypeScript, and Go validators use bounded subprocesses with timeout, process-tree termination, and capped diagnostics.

The invariant gate catches unresolved conflict blocks, placeholders, agent edit artifacts, assistant chatter, provenance text pasted into source, and explicit tautology tests. Most rules use added-line scope when raw diff is present.

The dependency gate compares Java/Python imports with local manifest, standard-library, and first-party evidence. The package-existence gate separately classifies a newly declared package as found, absent, or unverified against a registry/mirror.

### Behavioral evidence

Test adequacy blocks added test functions with no recognized assertion/expectation/verification call. Production changes without a conventional test remain advisory because repository test layouts vary.

The security gate is a changed-line tripwire for high-confidence credential formats, private-key headers, assigned secrets, and keyword-bound entropy. It complements full repository/history scanners.

Design rules enforce repository-owned forbidden/required patterns. Generic YAML rules support pattern, call-argument, and hollow-function checks without creating a new Java module per framework.

### Review focus

Density, cohesion, and duplication answer where a reviewer may need to look, not whether code is correct. Recommended profiles keep them advisory.

The external gate runs explicitly trusted tools and ingests SARIF. It preserves tool severity and reports unavailable/timeout/malformed output states.

### Governance surfaces

Documentation integrity checks local links, paths, cross-references, required mentions, and command completeness. PR metadata checks title/body/template/disclosure structure. License-header checks project-defined markers on new source files.

Jev is an optional decision-model adapter for calibrated questions that deterministic parsers cannot answer. It is always advisory and is declared as a model-backed external input.

## The artifact must not own the yardstick

The deepest trust failure in a repository gate is simple: if a pull request can edit the rules that judge it, the gate is only a suggestion.

Protecting `.aiv/config.yaml` alone is insufficient. Configuration can point at design rules, generic rules, documentation rules, and baselines. Those sidecars are policy too.

![AIV trust and evidence flow](../images/aiv-trust-evidence.png)

**Figure 4: Policy is pinned and frozen before use.** A symbolic ref is resolved once to a commit OID. AIV lists and reads every policy blob through that OID, rejects symlinks/path escapes/type changes, and materializes a private snapshot. Every policy-bearing gate consumes the snapshot. The verdict and audit record name the same commit.

For strict CI, AIV also compares the complete live `.aiv` tree with the trusted commit, including ignored and untracked files. This closes a less obvious bypass where a generated ignored rule file could influence a workspace glob without appearing in ordinary Git status.

The result is a small but important invariant:

```text
The bytes hashed into evidence are the bytes consumed by the gates.
```

## Strict machine input matters

Agent integration creates another trust boundary. A parser that accepts "almost JSON" can make two clients disagree about the same request.

AIV's shared `JsonLite` parser enforces RFC 8259 lexical rules:

- only space, tab, LF, and CR are accepted as whitespace,
- raw control characters cannot appear inside strings,
- Unicode escapes require four hexadecimal digits,
- leading plus/zero and missing fraction/exponent digits are rejected,
- non-ASCII number digits are rejected,
- trailing input and malformed containers fail.

`MemoryDiffProvider`, ordinary `.json` syntax validation, and `aiv verify` all use that same parser.

## One JSON request, one JSON verdict

![AIV verify interface](../images/aiv-verify-flow.png)

**Figure 5: `aiv verify` is the language-neutral contract.** It accepts strict JSON from stdin or a file, resolves/freeze policy, runs the same gates, and writes one schema-v2 result to stdout. Diagnostics stay on stderr.

Example request:

```json
{
  "artifact": {
    "baseRef": "memory",
    "headRef": "proposed",
    "rawDiff": "",
    "files": [
      {"path":"src/A.java","changeType":"MODIFIED","content":"class A {}"}
    ]
  },
  "policyRef": "origin/main",
  "context": {"workspace":"."}
}
```

Example call:

```bash
java -jar aiv-cli.jar verify \
  --request change.json \
  --workspace . \
  --policy-ref origin/main \
  --require-trusted-policy \
  --audit-log .aiv/audit.log
```

The response includes verdict, findings, policy source/ref/commit/digest, evidence digest, `reproducible_local`, `external_inputs`, and optional audit head.

The evidence digest binds:

```text
exact request bytes
+ canonical resolved config
+ exact frozen policy sidecar paths/bytes
+ pinned policy commit
```

## Visible uncertainty is part of correctness

A tool can be unavailable without the code being valid. A registry can time out without the package being real. A model can fail without the answer being "no."

AIV represents those cases rather than collapsing them into a false pass:

- `syntax.unverified`,
- package-existence unverified,
- external tool skipped/failed,
- Jev skipped/no-answer,
- `[POLICY-FALLBACK]`,
- `external_inputs` in verify output.

Strict profiles can turn appropriate unverified states into blockers. Advisory profiles can record them without stopping work.

## Evidence after the verdict

A gate result is more useful when it survives the ephemeral CI job.

`aiv-audit` writes each payload into a SHA-256 chain. Append uses an in-process monitor plus OS file lock, preventing concurrent workers from reading the same head and producing sibling records.

A chain alone is tamper-evident against ordinary edits; a privileged writer could rewrite the complete chain. Store the returned head outside the log and later verify with `--expect-head` to detect that rewrite.

`aiv-report` converts verified records into static HTML and JSONL. Reviewer labels can be joined by stable rule/file/line identity to calibrate which rules are candidates for stricter enforcement.

`aiv-proposal` applies template-driven checks to files, wiki-style pages, or issue trackers. KIP, FLIP, and SPIP are shipped templates; the engine is generic for RFC/ADR/proposal formats.

## AI-DLC: verification at phase boundaries

![AIV AI-DLC integration](../images/aiv-aidlc-flow.png)

**Figure 6: AI-DLC controls the lifecycle; AIV supplies evidence.** The plugin has an advisory sensor and a fail-closed sensor. File mode is fast/local. Full-request mode carries all changed files/raw diff for cross-file evidence.

Blocking mode requires a trusted policy ref and rejects declared package-registry, external-tool, or decision-model inputs. This keeps the deterministic phase contract explicit. A team can run a separate advisory sensor for external evidence.

## A practical adoption path

![AIV deployment topology](../images/aiv-deployment-map.png)

**Figure 7: Adoption has three surfaces.** Local feedback should be small. CI should be strict and policy-bound. Agent/lifecycle calls should use the versioned verify contract.

### Step 1: minimal local gate

Enable syntax and invariant. Learn the output and suppression model.

### Step 2: repository evidence

Add dependency, design, test adequacy, and security where they fit the language/repository. Keep density/cohesion/duplication advisory.

### Step 3: external prerequisites

Add package registries, SARIF tools, documentation/PR/header policy, and model advisory questions deliberately. Declare availability and failure behavior.

### Step 4: strict CI

Commit `.aiv/` to the primary branch and require:

```bash
--require-trusted-config --output-json ... --output-sarif ...
```

### Step 5: lifecycle integration

Use full-request `aiv verify` for agent or AI-DLC phase decisions. Add audit/anchor/report where decision history has operational value.

## What I learned building the trust boundary

The difficult part was not writing one more pattern. It was preserving meaning across boundaries.

"Base-ref config" was not enough because policy had sidecars.

"Check policy before use" was not enough because a concurrent writer could change it after the check.

"Git status is clean" was not enough because ignored files can still be consumed by globs.

"JSON parser" was not enough when it accepted YAML or Java number forms.

"Hash chain" was not enough without concurrent append locking and an external head.

"Same ref" was not enough until the ref was pinned once and the audit record used the same commit.

The general lesson is reusable: every product claim must be traced to exact bytes, exact execution, explicit uncertainty, and a consumer-visible result.

## Conclusion

Agentic development does not remove the need for engineering evidence. It increases the rate at which evidence must be produced.

AIV does not attempt to identify AI authorship or replace human judgement. It turns objective change facts into a policy-bound receipt before detailed review. That gives compilers, scanners, LLM reviewers, orchestrators, and humans a cleaner place to do their own jobs.

The practical value is not "AI code is bad." The value is that no change, human or generated, should ask a maintainer to rediscover facts a machine can establish reproducibly.

## Learn more

- [AIV Hello World](../HELLO-WORLD.md)
- [Module adoption guide](../MODULE-ADOPTION-GUIDE.md)
- [Architecture](../ARCHITECTURE.md)
- [Configuration reference](../DEVELOPER-CONFIGURATION.md)
- [AI-DLC integration](../AIDLC.md)
- [MCP / verify contract](../MCP.md)

**Author:** Viquar Khan  
**Email:** vaquar.khan@gmail.com
