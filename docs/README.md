# AIV documentation

## Learn the product

1. **[Quick start](TUTORIAL.md):** build current source, choose a profile, run locally and in strict CI.
2. **[Hello World](HELLO-WORLD.md):** a 30-step lab exercising every gate one by one.
3. **[Module adoption guide](MODULE-ADOPTION-GUIDE.md):** which modules/gates are mandatory, optional, advisory, or standalone.
4. **[Architecture](ARCHITECTURE.md):** all 25 projects, runtime/trust/evidence/integration flows with eight explained PNG diagrams.
5. **[InfoQ-style article](blog/AIV-VERIFIABLE-CHANGE.md):** long-form problem, design, lessons, and adoption narrative.

## Operate AIV

| Guide | Purpose |
|-------|---------|
| [Configuration](DEVELOPER-CONFIGURATION.md) | every gate/global key/default |
| [Deployment](DEPLOYMENT.md) | local/CI/jar/Action/permissions/evidence |
| [Pre-commit](PRE-COMMIT.md) | staged change workflow |
| [MCP / verify](MCP.md) | strict schema-v2 machine contract |
| [AI-DLC](AIDLC.md) | advisory/fail-closed sensors and plugin |
| [CI snippets](CI-SNIPPETS.md) | GitLab/Azure/pre-push examples |
| [Evidence and proposals](EVIDENCE-AND-PROPOSALS.md) | audit/report/proposal operations |
| [Security gate](PLUGIN-SECURITY.md) | secret tripwire configuration |

## Build and extend

| Guide | Purpose |
|-------|---------|
| [Developer guide](DEVELOPER-GUIDE.md) | build/test/add gate/rule pack |
| [Maven/version](MAVEN-VERSION.md) | artifact coordinates and release version |
| [Why not PMD/Semgrep](WHY-NOT-PMD-SEMGREP.md) | complementary positioning |
| [Pipeline with advisory review](pipeline-aiv-copilot.md) | deterministic first, model advice second |
| [Documentation map](DOCUMENTATION-MAP.md) | public docs vs examples/generated/internal evidence |

## Product/evidence assets

- [Generated diagrams](images/)
- [Module READMEs](../aiv-api/README.md): every `aiv-*` module has a detailed guide.

## Specialized examples

- [Airflow rule-pack tutorial](AIRFLOW-TUTORIAL.md): one framework example, not the product identity.
- [AI-DLC integration source](../integrations/aidlc/README.md).
- [Consumer example project](../example-project/README.md).

**Author:** Viquar Khan
