# AIV Integrity Gate

## One sentence

**The air-gapped PR filter for AI-generated code slop.**

Everything below is supporting detail: what it checks, how it differs from generic linters, and how to run it without sending code to a third party or calling an LLM.

### Why not PMD, Semgrep, or Checkstyle?

They are great **whole-repo** static analyzers. AIV is a **diff-scoped** integrity gate (density, design YAML, imports vs manifests, optional docs). See the comparison grid below and [docs/WHY-NOT-PMD-SEMGREP.md](docs/WHY-NOT-PMD-SEMGREP.md).

| | **AIV** | **PMD / Semgrep / Checkstyle** |
|--|---------|--------------------------------|
| **Scope** | PR diff by default | Project-wide rulesets |
| **Sweet spot** | Low-signal / boilerplate / design & import surface on **changed** files | Bugs, style, security patterns across the tree |
| **Config** | `.aiv/config.yaml` + rules in-repo | Tool-specific XML/YAML |
| **Air gap** | Single shaded JAR + local rules | Varies; all can run offline |

### Identity (licensing vs project)

- **Maven coordinates:** `io.github.vaquarkhan` - this is **not** an Apache Software Foundation (ASF) project.
- **License:** [Apache License 2.0](LICENSE) applies to **this software’s source** (standard OSS license text). It does **not** mean ASF incubation or `org.apache.*` packages.

**Author:** Vaquar Khan

---

## See it in CI (no marketing fluff - just the check)

- **Live runs:** [GitHub Actions on this repository](https://github.com/vaquarkhan/aiv-integrity-gate/actions) - open a workflow run and expand the job to see pass/fail and logs.
- **What to look for:** a failing run when a change trips **density** (too little real logic), **design** (forbidden calls or slop markers), or **dependency** (imports not backed by your lockfile). That is the same signal your contributors will see.
- **Optional media:** If you add a screen recording, store it at `docs/images/aiv-ci-demo.gif` and reference it here so newcomers see a red check on a bad PR in one glance.

---

## Quick start (about five minutes)

You are done when a PR runs AIV and prints a report (pass or fail).

1. **Download the CLI (no clone required)** - From Maven Central, fetch the shaded uber JAR. The version must match the reactor `<version>` in this project’s root [`pom.xml`](pom.xml) (current line: **1.0.4**). See [docs/MAVEN-VERSION.md](docs/MAVEN-VERSION.md) for `${project.version}`, coordinates, and scripted downloads.

   ```bash
   curl -fsSL -o aiv-cli.jar "https://repo1.maven.org/maven2/io/github/vaquarkhan/aiv/aiv-cli/1.0.4/aiv-cli-1.0.4.jar"
   ```

   From a **clone** of this repo you can resolve the version with Maven instead of hardcoding it:

   ```bash
   VERSION="$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version -f pom.xml)"
   curl -fsSL -o aiv-cli.jar "https://repo1.maven.org/maven2/io/github/vaquarkhan/aiv/aiv-cli/${VERSION}/aiv-cli-${VERSION}.jar"
   ```

   Or use [scripts/install-aiv.sh](scripts/install-aiv.sh) or [scripts/print-maven-version.sh](scripts/print-maven-version.sh) (same version source, configurable `AIV_VERSION`).

2. **Bootstrap config** - `java -jar aiv-cli.jar init --workspace .` writes `.aiv/config.yaml` and starter `.aiv/design-rules.yaml` from a quick language sniff, or copy from [`example-project/`](example-project/).

3. **Wire CI** - Prefer the [composite action](action.yml) (`vaquarkhan/aiv-integrity-gate@v1`) which downloads the same JAR, or adapt [`example-project/.github/workflows/aiv.yml`](example-project/.github/workflows/aiv.yml). Update branch names if yours are not `main` / `master`.

4. **Open a pull request** - Intentionally violate a design rule (e.g. `System.exit` with the sample rules) to see a **fail**, then fix to **pass**.

**Developers changing AIV itself:** build with `mvn clean verify -pl aiv-cli -am` and run `java -jar aiv-cli/target/aiv-cli-<version>.jar`. User guides: [docs/TUTORIAL.md](docs/TUTORIAL.md), [docs/MAVEN-VERSION.md](docs/MAVEN-VERSION.md), [DEPLOYMENT.md](docs/DEPLOYMENT.md), [DEVELOPER-CONFIGURATION.md](docs/DEVELOPER-CONFIGURATION.md). **Release notes:** [CHANGELOG.md](CHANGELOG.md).

---

## Problems and Solutions

| Pain Area | What Happens | Feature That Addresses It |
|-----------|--------------|---------------------------|
| Reviewer overload | Too many PRs to review; maintainers spend time on low-value contributions | Density gate filters verbose, boilerplate-heavy code before human review |
| Low-quality contributions | Code that looks correct but is mostly scaffolding, empty classes, or copy-paste | Density gate (logic density ratio and entropy) flags it |
| AI slop | AI-generated code with emoji, attribution markers, or generic boilerplate | Design rules (`no-emoji-in-code`, `no-ai-generated-markers`) flag it |
| Design drift | Code violates project architecture, RFCs, or forbidden patterns | Design gate enforces YAML rules (forbidden and required patterns) |
| Wrong API usage | Contributors use deprecated APIs or wrong patterns (e.g. in-memory list instead of ExpireSnapshots) | Design gate catches forbidden calls and missing required calls |
| Unknown imports | Typos in package names or imports not declared in lockfile; supply-chain risk | Dependency gate validates Java imports vs pom.xml, Python vs requirements.txt |
| Fragile edge-case code | Code passes example tests but fails on boundary inputs | Invariant gate (placeholder for property-based tests) |
| Urgent merges | Need to bypass checks for hotfix or emergency | `/aiv skip` on its own line in the **latest** PR commit skips all gates (optional `skip_allowlist` in config) |
| Refactors flagged | Legitimate refactors remove more lines than they add; density gate would fail | Refactor exception: density skips when net lines <= threshold (default -50) |
| Core maintainer friction | Trusted committers get unnecessary density failures | Trusted authors bypass density check |
| Issue squatting | Contributors get assigned, then ghost or submit low-quality code | Assignment Gate: assign only after PR passes AIV |

---

## What This Tool Does

When someone opens a pull request, AIV runs a set of checks on the changed files:

1. **Density** - Does the code have enough real logic, or is it mostly scaffolding? Empty classes and copy-paste boilerplate get flagged.

2. **Design** - Does the code follow your project's rules? You define forbidden patterns (for example, do not use `System.exit`) and required patterns (for example, use a specific API when a keyword appears). Includes rules for **AI slop detection**: emoji and attribution markers (e.g. "Generated by ChatGPT") common in low-quality AI-generated code.

3. **Dependency** - Are new imports in Java and Python files declared in your lockfile? This helps catch typos and supply-chain attacks where someone registers a fake package name.

4. **Invariant** - Placeholder for property-based tests. The gate passes by default; real invariant checks run in your project's test phase.

5. **Doc Integrity** - Validates documentation files (.md, .txt, .rst): path existence, cross-references, required mentions, command completeness, path fabrication. Opt-in via `--include-doc-checks` or config with `auto: true` to run only when diff has doc files.

AIV works with Java, Python, Go, Rust, Kotlin, Scala, JavaScript, TypeScript, C, C++, Ruby, and shell. The density gate runs full logic checks on Java only; other languages get entropy checks. Design and dependency checks apply to whatever languages you configure.

No API keys or paid services are required. Everything runs locally in your CI.

---

## Modules

| Module | Purpose |
|--------|---------|
| `aiv-api` | Interfaces, models, and extension points |
| `aiv-core` | Orchestrator that runs gates in sequence |
| `aiv-plugin-density` | Logic density and entropy checks |
| `aiv-plugin-design-lucene` | Design compliance via YAML rules |
| `aiv-plugin-dependency` | Import validation against pom.xml and requirements.txt |
| `aiv-plugin-invariant-template` | Invariant gate (passes by default) |
| `aiv-plugin-doc-integrity` | Documentation integrity (paths, cross-refs, commands) |
| `aiv-adapter-git` | Git diff provider |
| `aiv-adapter-github` | Report publisher (stdout / SLF4J for CI logs) |
| `aiv-cli` | Command-line entry point |

---

## Build and Run

Build the project:

```bash
mvn clean package
```

Run AIV from the repo root (compare your working tree to `origin/main`). Replace the JAR name with your Maven `${project.version}` (for example `1.0.4`):

```bash
java -jar aiv-cli/target/aiv-cli-1.0.4.jar --diff origin/main
```

Or via Maven:

```bash
mvn -pl aiv-cli exec:java -Dexec.args="--diff origin/main"
```

**Exit codes**

| Code | Meaning |
|------|---------|
| `0` | All blocking gates passed (or `doctor` mode - informational only). |
| `1` | At least one blocking gate failed. |
| `2` | Invalid arguments or configuration (including unreadable `--output-json` path). |
| `3` | Git subprocess failure (bad ref, dirty state, etc.). |
| `4` (optional) | Set with `--warnings-exit-code 4` when the run **passed** but emitted **notices** (e.g. oversized files skipped from scanning). Default remains `0` in that case. |

**Structured output:** `--output-json path/to/aiv-report.json` writes `schema_version: 2` with per-gate **`findings`** (file, line, message). **`--output-sarif`** writes SARIF 2.1.0; **`--publish-github-checks`** posts a GitHub Check with annotations (see [docs/DEVELOPER-CONFIGURATION.md](docs/DEVELOPER-CONFIGURATION.md)). See [CHANGELOG.md](CHANGELOG.md).

### Working on this codebase

If you are changing AIV itself, run a full validation (unit tests plus JaCoCo line coverage checks):

```bash
mvn clean verify
```

The parent POM enforces **100% line coverage** per module on `verify`. If the build fails at `jacoco:check`, open `MODULE/target/site/jacoco/index.html` for that module and add or extend tests for the red lines.

On GitHub, [`.github/workflows/aiv.yml`](.github/workflows/aiv.yml) runs **`mvn clean verify -pl aiv-cli -am`** (unit tests and JaCoCo) before executing the AIV gate on the diff.

---

## Example Project

The `example-project/` directory contains a minimal setup: config files, design rules, and a GitHub Actions workflow. Use it to see how AIV behaves on real code.

Validate locally (from the parent repo):

```bash
scripts\validate-example.bat   # Windows
./scripts/validate-example.sh  # Linux or Mac
```

---

## Releasing (maintainers)

1. **GitHub Release (JAR):** push a tag `vX.Y.Z` - workflow **Release (GitHub)** runs `mvn verify` and uploads `aiv-cli-X.Y.Z.jar` to the [Releases](https://github.com/vaquarkhan/aiv-integrity-gate/releases) page.  
2. **Maven Central:** add repository secrets (`CENTRAL_TOKEN_*`, `GPG_*`), then run **Actions → Publish to Maven Central** with version `X.Y.Z` (after the GitHub release).  
3. **Marketplace:** publish the root **`action.yml`** from the GitHub UI when you want listing updates.

Details: [DEPLOYMENT.md](docs/DEPLOYMENT.md) (GitHub release, Maven Central, `cli-jar-url`).

---

## Documentation

| Document | Contents |
|----------|----------|
| [docs/TUTORIAL.md](docs/TUTORIAL.md) | Long-form getting started (walkthrough, CLI, CI, troubleshooting). |
| [docs/README.md](docs/README.md) | Index of all guides. |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Enable AIV in your repo, CI workflows, Maven Central / Marketplace publishing. |
| [docs/DEVELOPER-CONFIGURATION.md](docs/DEVELOPER-CONFIGURATION.md) | Full configuration reference for gates and rules. |
| [docs/dashboard/README.md](docs/dashboard/README.md) | Static dashboard for JSON run history. |
| `*/README.md` (per module) | Short module-specific notes (API, core, plugins, adapters, CLI). |

---

## Minimal Configuration

Create `.aiv/config.yaml`:

```yaml
# Optional: skip generated paths
# exclude_paths:
#   - "**/generated/**"
#   - "**/*.pb.java"

gates:
  - id: density
    enabled: true
    config:
      ldr_threshold: 0.25
      entropy_threshold: 3.8
  - id: design
    enabled: true
    config:
      rules_path: .aiv/design-rules.yaml
  - id: dependency
    enabled: true
  - id: invariant
    enabled: true
  - id: doc-integrity
    enabled: false
    config:
      rules_path: .aiv/doc-rules.yaml
      auto: true
```

Create `.aiv/design-rules.yaml` with at least one constraint:

```yaml
constraints:
  - id: no-system-exit
    keywords: []
    forbidden_calls: [System.exit]
    required_calls: []

  # AI slop detection: emoji and attribution markers (common tells of AI-generated code)
  - id: no-emoji-in-code
    keywords: []
    forbidden_calls: ["\u2705", "\u274C"]
    required_calls: []

  - id: no-ai-generated-markers
    keywords: []
    forbidden_calls: ["Generated by AI", "AI-generated"]
    required_calls: []

  - id: use-specific-api
    keywords: [expireSnapshots]
    forbidden_calls: [table.removeSnapshots]
    required_calls: [ExpireSnapshots]
```

Optional: add `.aiv/doc-rules.yaml` for documentation validation (paths, cross-refs, commands). Enable with `--include-doc-checks` or set `doc-integrity` gate to `enabled: true` in config.

If you omit these files, AIV uses built-in defaults and looks for `.aiv/design-rules.yaml` when the design gate is enabled.

---

## CI Integration

### GitHub Action (Marketplace / composite)

After **`io.github.vaquarkhan.aiv:aiv-cli`** is published to Maven Central (see [DEPLOYMENT.md](docs/DEPLOYMENT.md#option-b-deploy-to-maven-central)), consumers can run the **root** composite action. It downloads the **shaded** uber JAR from Central by default:

```yaml
jobs:
  aiv:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5
        with:
          fetch-depth: 0
      - uses: vaquarkhan/aiv-integrity-gate@v1
        with:
          base-ref: origin/${{ github.base_ref }}
          aiv-version: '1.0.4'
```

Optional input **`cli-jar-url`** points to a full URL for the shaded JAR (for example a GitHub Release asset) when you do not want the Central download. Inputs are documented in [action.yml](action.yml).

### Raw shell step

Add a step to your pipeline:

```yaml
- run: java -jar aiv-cli.jar --workspace . --diff origin/main
```

Append `--include-doc-checks` to that command when you want the doc-integrity gate on every run without editing `config.yaml`. The job fails when any gate fails. No secrets are required for the default setup.

---

## Human Override and Exceptions

**Skip all gates:** Put **`/aiv skip`** or **`aiv skip`** on its **own line** in the **latest** commit message on the PR head (anchored match - not a substring inside unrelated text). If **`skip_allowlist`** is set in `.aiv/config.yaml`, only those author emails may use the directive.

**Refactoring:** Per-file net LOC can exempt density checks for deletions-heavy changes (see configuration reference).

**Trusted authors:** List committer email addresses in `trusted_authors` under the density gate config. Those authors bypass the density check.

---

## Roadmap (short)

Shipped foundations: **init**, **doctor**, **explain**, per-gate **warn** severity, **JSON report** (`--output-json`, `schema_version: 2`), **SARIF** (`--output-sarif`), **GitHub Checks** (`--publish-github-checks`). Next: **baseline** suppressions, optional **`aiv-plugin-security`** (not in the repo yet — see [docs/PLUGIN-SECURITY.md](docs/PLUGIN-SECURITY.md)). See [CHANGELOG.md](CHANGELOG.md).

## License

Licensed under the **Apache License 2.0** (see [LICENSE](LICENSE)). This is a license on the **source code**, not affiliation with the Apache Software Foundation.
