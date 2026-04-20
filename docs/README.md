# Documentation index

All guides for **AIV Integrity Gate** live under this directory unless noted otherwise.

## Start here

| Document | Audience | Description |
|----------|----------|-------------|
| **[TUTORIAL.md](TUTORIAL.md)** | New users | Long-form, step-by-step guide: concepts, CLI install, local run, GitHub Actions, tuning, troubleshooting, FAQ. |
| **[WHY-NOT-PMD-SEMGREP.md](WHY-NOT-PMD-SEMGREP.md)** | Evaluators & leads | How AIV differs from PMD, Semgrep, and Checkstyle (scope and when to combine). |
| **[DEPLOYMENT.md](DEPLOYMENT.md)** | Admins & maintainers | Enabling AIV in a repo, publishing the CLI (GitHub Releases, Maven Central), composite action inputs. |
| **[DEVELOPER-CONFIGURATION.md](DEVELOPER-CONFIGURATION.md)** | Power users | Complete reference for `.aiv/config.yaml`, design/doc rules, CLI flags, CI snippets. |
| **[MAVEN-VERSION.md](MAVEN-VERSION.md)** | Integrators & release | Reactor `${project.version}`, Maven Central URL pattern, `mvn help:evaluate`, aligning action defaults with the POM. |

## Optional topics

| Resource | Description |
|----------|-------------|
| **[dashboard/README.md](dashboard/README.md)** | Static HTML dashboard (light/dark, charts) for visualizing exported JSON runs. |
| **[../README.md](../README.md)** | Project overview, quick start, module table, license. |
| **[../example-project/README.md](../example-project/README.md)** | Minimal sample repo layout inside this project. |

## Conventions

- **Version:** the numeric release is always the `<version>` in the reactor root [`pom.xml`](../pom.xml). See **[MAVEN-VERSION.md](MAVEN-VERSION.md)** for `${project.version}`, Central coordinates, and copy-paste commands.
- **Maven Central path** for the shaded CLI: `io/github/vaquarkhan/aiv/aiv-cli/<version>/aiv-cli-<version>.jar` under `https://repo1.maven.org/maven2/`.

**Author:** Vaquar Khan
