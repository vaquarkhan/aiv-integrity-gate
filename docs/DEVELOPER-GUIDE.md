# Developer guide

How to **build, run, test, and extend** AIV Integrity Gate as a contributor to this repository.

For *using* AIV as a consumer on another project, start with [TUTORIAL.md](TUTORIAL.md) and [DEPLOYMENT.md](DEPLOYMENT.md). For module layout and diagrams, see [ARCHITECTURE.md](ARCHITECTURE.md).

## Prerequisites

| Tool | Notes |
|------|--------|
| **JDK 17+** | Temurin recommended |
| **Maven 3.9+** | On `PATH`, or any distribution you already use |
| **Git** | Required for local diffs |
| **Python (optional)** | Only for live Python syntax checks; missing → syntax gate skips Python |

Do **not** commit IDE/agent local state (`.cursor/`, `.idea/`, etc.). Those paths are gitignored.

## Clone and build

```bash
git clone https://github.com/vaquarkhan/aiv-integrity-gate.git
cd aiv-integrity-gate
mvn clean verify
```

Parent POM enforces **100% line coverage** per module. A red `jacoco:check` means open `MODULE/target/site/jacoco/index.html` and cover the missed lines.

Faster iteration while changing one plugin:

```bash
mvn -pl aiv-plugin-syntax -am verify
mvn -pl aiv-cli -am -DskipTests package
```

Shaded CLI output:

```text
aiv-cli/target/aiv-cli-1.0.4.jar
```

(Version follows the reactor `<version>` in the root `pom.xml`.)

## Run the CLI against this repo

From the reactor root (after `package` or `verify`):

```bash
# Clear noisy JVM banners on Windows if needed
# PowerShell: $env:JAVA_TOOL_OPTIONS=''

java -jar aiv-cli/target/aiv-cli-1.0.4.jar --version

java -jar aiv-cli/target/aiv-cli-1.0.4.jar `
  --workspace . `
  --diff origin/main `
  --head HEAD
```

Empty / same-ref smoke (often used in tests):

```bash
java -jar aiv-cli/target/aiv-cli-1.0.4.jar --workspace . --diff HEAD --head HEAD
```

### Useful flags

| Flag | Purpose |
|------|---------|
| `--output-json path` | Machine-readable report (`schema_version: 2`, check `doctor_mode`) |
| `--output-sarif path` | SARIF 2.1.0 |
| `--quiet` | Suppress human stdout report |
| `doctor` subcommand | Advisory run; exit 0; JSON sets `doctor_mode: true` |
| `explain <gate-id>` | Offline help (`syntax`, `density`, …) |
| `init --workspace .` | Write starter `.aiv/` config (optional `--preset agent-paste-strict\|java-ci\|minimal`) |

Exit codes: `0` pass, `1` gate fail, `2` bad args/config, `3` git failure.

### Python syntax override

```bash
java -Daiv.python.command=py -jar aiv-cli/target/aiv-cli-1.0.4.jar --workspace . --diff origin/main
```

## Dogfood config in this repository

This repo’s own gate config lives at [`.aiv/config.yaml`](../.aiv/config.yaml):

- **Enabled:** density, design, dependency, **syntax**
- **Disabled:** invariant (placeholder scanning can over-fire), doc-integrity (optional)

CI workflow [`.github/workflows/aiv.yml`](.github/workflows/aiv.yml) runs `mvn clean verify -pl aiv-cli -am` then executes the shaded JAR on the PR diff.

## Day-to-day contributor workflow

1. Branch from `main` (feature branch).
2. Implement + tests in the same module (JaCoCo will fail otherwise).
3. `mvn clean verify` locally before push.
4. Open a PR; AIV Gate must pass on the diff.
5. Do not add Cursor/agent attribution trailers to commits.

### Extending with a new gate

Follow the checklist in [ARCHITECTURE.md](ARCHITECTURE.md#adding-a-new-gate-maintainer-pattern). Pattern summary: module → `QualityGate` → ServiceLoader file → root + CLI POM → 100% coverage → `explain/<id>.md`.

### Changing the released version

1. Bump `<version>` in the root `pom.xml`.
2. Run `scripts/sync-action-version.ps1` or `scripts/sync-action-version.sh` so `action.yml` `aiv-version` matches.
3. Update [CHANGELOG.md](../CHANGELOG.md).
4. Tag / release / Central publish per [DEPLOYMENT.md](DEPLOYMENT.md).

## Tests that matter

| Command | What it proves |
|---------|----------------|
| `mvn -pl aiv-plugin-<name> -am verify` | Unit tests + coverage for one gate |
| `mvn clean verify` | Full reactor |
| CLI smoke above | ServiceLoader wiring in the shaded JAR |

Prefer **package-private** test hooks over reflection for adapter/plugin internals (project convention).

## Troubleshooting

| Symptom | Likely cause |
|---------|----------------|
| `jacoco:check` failed | Missed lines in new code — open the HTML report |
| Syntax fails on valid Python with odd bytes | Diff extraction artifact — current Python checker treats non-`SyntaxError` as skip |
| Dependency flags `os` / `json` | Old CLI without stdlib allowlist — rebuild from this branch |
| Gate missing from report | Not on classpath (CLI POM) or `enabled: false` in config |
| `explain` empty | Missing `explain/<id>.md` resource |

## Related docs

| Doc | Audience |
|-----|----------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Maintainers — diagrams & module map |
| [TUTORIAL.md](TUTORIAL.md) | Consumers — first setup |
| [DEVELOPER-CONFIGURATION.md](DEVELOPER-CONFIGURATION.md) | Config reference |
| [pipeline-aiv-copilot.md](pipeline-aiv-copilot.md) | Optional Stage-2 advisory review |

**Author:** Vaquar Khan
