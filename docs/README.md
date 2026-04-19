# Documentation index

All guides for **AIV Integrity Gate** live under this directory unless noted otherwise.

## Start here

| Document | Audience | Description |
|----------|----------|-------------|
| **[TUTORIAL.md](TUTORIAL.md)** | New users | Long-form, step-by-step guide: concepts, CLI install, local run, GitHub Actions, tuning, troubleshooting, FAQ. |
| **[DEPLOYMENT.md](DEPLOYMENT.md)** | Admins & maintainers | Enabling AIV in a repo, publishing the CLI (GitHub Releases, Maven Central), composite action inputs. |
| **[DEVELOPER-CONFIGURATION.md](DEVELOPER-CONFIGURATION.md)** | Power users | Complete reference for `.aiv/config.yaml`, design/doc rules, CLI flags, CI snippets. |

## Optional topics

| Resource | Description |
|----------|-------------|
| **[dashboard/README.md](dashboard/README.md)** | Static HTML dashboard (light/dark, charts) for visualizing exported JSON runs. |
| **[../README.md](../README.md)** | Project overview, quick start, module table, license. |
| **[../example-project/README.md](../example-project/README.md)** | Minimal sample repo layout inside this project. |

## Conventions

- **Version examples** use the current release line (e.g. `1.0.2`); substitute the version you depend on.
- **Maven Central path** for the shaded CLI: `io/github/vaquarkhan/aiv/aiv-cli/<version>/aiv-cli-<version>.jar` under `https://repo1.maven.org/maven2/`.

**Author:** Vaquar Khan
