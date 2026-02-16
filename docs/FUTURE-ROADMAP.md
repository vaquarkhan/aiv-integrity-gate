# AIV Future Roadmap

Features and capabilities not yet supported in the AIV project. This document lists planned work and gaps for future releases.

**Author:** Vaquar Khan

---

## Summary: What Is Not Supported Yet

| Category | Item | Status |
|----------|------|--------|
| Design validation | RAG-based semantic validation against design docs | Not implemented |
| Invariant gate | Property-based test synthesis and execution | Placeholder only |
| Legal | Snippet match / tainted code detection | Not implemented |
| Configuration | Path exclusions (skip certain files or directories) | Not implemented |
| Output | JUnit XML report | Not implemented |
| Output | JSON report | Not implemented |
| Output | GitHub PR comment | Not implemented |
| Deployment | Docker image (`apache/aiv-gate`) | Not implemented |
| CI | Reusable workflow (`apache/infrastructure-actions`) | Not implemented |
| CI | GitHub App (webhook-based, no CI config) | Not implemented |
| Density | Full LDR for Python, Go, Rust (AST-based) | Java only today |
| Design | Config switch: `design.mode: lucene | rag` | Lucene only |
| Invariant | Config switch: `invariant.strategy: template | llm_synthesis` | Template only (passes) |
| Report | Slack, Jira, custom dashboards | Stdout only |
| CI adapters | GitLab, Buildkite, Azure DevOps | GitHub Actions, Jenkins via docs only |
| Metrics | Custom metric SPI | Fixed LDR and entropy |

---

## 1. Design Validation (Module B: Semantic Architect)

**Status:** Not implemented.

**Current:** Design gate uses YAML rules with substring matching (`forbidden_calls`, `required_calls`). No semantic understanding of design documents.

**Missing:**
- RAG-based validation against `.design/`, `docs/`, or `format/spec.md`
- Embedding model to vectorize design docs
- Vector store (e.g., FAISS) for retrieval
- LLM judge to validate diff against retrieved context
- Config: `design.mode: rag` and `design.provider: openai | anthropic | ollama`

**Use case:** Catch subtle architectural violations (e.g., "absolute paths" in a Spec V4 "relative path" world) that are hard to express as YAML patterns.

**Dependencies:** API keys, paid services (or self-hosted LLM), optional plugin `aiv-plugin-design-rag`.

---

## 2. Invariant Synthesizer (Module C: Full Implementation)

**Status:** Placeholder. Gate passes by default.

**Current:** `aiv-plugin-invariant-template` is a stub. No test generation or execution.

**Missing:**
- Extract method signatures from changed code
- Generate property-based tests (Hypothesis for Python, jqwik for Java)
- Execute tests in sandbox
- Report counter-examples when tests fail
- Config: `invariant.strategy: llm_synthesis` with LLM for test generation

**Use case:** Force contributors to debug property-test failures, proving they understand the code ("Reasonable Human Effort").

**Dependencies:** Test execution in CI, optional LLM for synthesis (or template-based without LLM).

---

## 3. Tainted Code / Snippet Match (Gap 2)

**Status:** Not implemented.

**Current:** No license or provenance analysis.

**Missing:**
- Snippet matcher against known copyrighted/problematic code
- Database of known GPL or other non-permissive snippets
- Threshold for "identifiable" chunk size
- Integration with ASF Legal requirements

**Use case:** Reduce risk of code from models trained on non-permissive (e.g., GPL) data.

**Dependencies:** Legal review, snippet database maintenance, new plugin.

---

## 4. Path Exclusions

**Status:** Not implemented.

**Current:** All changed files matching configured extensions are checked. No way to exclude paths.

**Missing:**
- Config: `exclude_paths` or `include_paths` per gate or globally
- Glob patterns (e.g., `**/generated/**`, `**/*.pb.java`)
- Support in density, design, and dependency gates

**Use case:** Skip generated code, vendored code, or third-party stubs.

**Workaround:** Disable gates or adjust thresholds.

---

## 5. Report Output Formats

**Status:** Stdout only.

**Current:** `StdoutReportPublisher` prints a simple text report to stdout.

**Missing:**
- JUnit XML report (for CI integration, e.g., Jenkins test results)
- JSON report (for tooling, dashboards)
- GitHub PR comment (post result as comment on PR)
- Config: `--output junit.xml` or `--output json`

**Use case:** Better CI integration, custom dashboards, PR visibility.

---

## 6. Docker Image

**Status:** Not implemented.

**Current:** Users run `java -jar aiv-cli.jar` or build from source. No official Docker image.

**Missing:**
- `Dockerfile` for `apache/aiv-gate` or `apache/aiv-gate:latest`
- Image published to Docker Hub or GitHub Container Registry
- CI workflows that use `docker run apache/aiv-gate aiv run ...`

**Use case:** Consistent deployment across platforms.

---

## 7. Reusable Workflow / infrastructure-actions

**Status:** Not implemented.

**Current:** Projects copy `.github/workflows/aiv.yml` and clone/build AIV from source.

**Missing:**
- `apache/infrastructure-actions` repo with reusable AIV workflow
- `uses: apache/infrastructure-actions/aiv@main`
- Published AIV CLI JAR (Maven Central or GitHub Releases) so workflow does not need to build

**Use case:** One-line opt-in for Apache projects.

---

## 8. GitHub App

**Status:** Not implemented.

**Current:** AIV runs as a CI job. Requires workflow file in repo.

**Missing:**
- GitHub App that receives webhooks on PR events
- Runs AIV server-side, posts checks and comments
- No CI config needed in repo

**Use case:** Zero-config adoption for projects that prefer GitHub Apps over CI workflows.

---

## 9. Density Gate: Full LDR for Non-Java

**Status:** Java only for LDR.

**Current:** Logic Density Ratio (LDR) is computed for Java only. Other languages (Python, Go, Rust, etc.) get entropy checks only.

**Missing:**
- AST parsing for Python (e.g., `ast` or tree-sitter)
- AST parsing for Go, Rust, Scala
- LDR calculation for non-Java files

**Use case:** Consistent density checks across polyglot repos.

---

## 10. Design Mode: Config Switch (Lucene vs RAG)

**Status:** Lucene only.

**Current:** Design gate uses YAML rules with Lucene for matching. No config switch.

**Missing:**
- Config: `design.mode: lucene | rag`
- When `rag`, use design-rag plugin instead of design-lucene
- Provider config: `design.provider: openai | anthropic | ollama`

**Use case:** Projects can opt into RAG when they have budget and API keys.

---

## 11. Invariant Strategy: Config Switch

**Status:** Template only (and passes by default).

**Current:** No real invariant checks. No strategy config.

**Missing:**
- Config: `invariant.strategy: template | llm_synthesis`
- Template-based: run jqwik templates (no LLM)
- LLM synthesis: generate tests from signatures, run, fail on counter-example

**Use case:** Enforce understanding via property tests.

---

## 12. Report Publisher SPI

**Status:** Stdout only.

**Current:** `ReportPublisher` interface exists; only `StdoutReportPublisher` is implemented.

**Missing:**
- `JUnitXmlReportPublisher`
- `JsonReportPublisher`
- `GitHubCommentReportPublisher` (requires GitHub token)
- `SlackReportPublisher`, `JiraReportPublisher` (future)

**Use case:** Integrate with Slack, Jira, custom dashboards.

---

## 13. CI/CD Adapters

**Status:** Documentation only; no native adapters.

**Current:** Docs show how to add AIV to GitHub Actions and Jenkins. No `CICDAdapter` implementations.

**Missing:**
- `aiv-adapter-gitlab`
- `aiv-adapter-buildkite`
- `aiv-adapter-azure`
- Platform-specific config and wiring

**Use case:** First-class support for GitLab, Buildkite, Azure Pipelines.

---

## 14. Custom Metric SPI

**Status:** Not implemented.

**Current:** Density gate uses fixed LDR and entropy. No custom metrics.

**Missing:**
- `MetricCalculator` SPI
- Projects can plug in custom metrics (e.g., cyclomatic complexity, custom density formula)
- Config to enable/disable custom metrics

**Use case:** Project-specific quality thresholds.

---

## 15. Published CLI

**Status:** Not implemented.

**Current:** Users run `java -jar aiv-cli-1.0.0-SNAPSHOT.jar` from local build.

**Missing:**
- AIV CLI published to Maven Central
- AIV CLI published to GitHub Releases
- Versioned artifacts (e.g., `aiv-cli-1.0.0.jar`)

**Use case:** Download without building; simpler CI workflows.

---

## Priority Overview

| Priority | Items |
|----------|-------|
| High | Path exclusions, JUnit XML, JSON report, published CLI, Docker image |
| Medium | RAG design plugin, invariant synthesis, design/invariant config switches |
| Low | Snippet match, GitHub App, custom metrics, Slack/Jira publishers |

---

