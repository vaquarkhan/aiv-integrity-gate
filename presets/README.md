# AIV configuration presets

Presets are explicit starter policies copied into `.aiv/config.yaml` by `aiv init`. Every shipped preset names all 16 gates, so omitted-gate fallback cannot silently change its behavior.

```bash
java -jar aiv-cli.jar init --preset minimal --workspace .
java -jar aiv-cli.jar init --preset java-ci --workspace .
java -jar aiv-cli.jar init --preset agent-paste-strict --workspace .
java -jar aiv-cli.jar init --preset asf --workspace .
java -jar aiv-cli.jar init --preset airflow --workspace .
```

Review and commit the generated `.aiv/` directory to the primary branch before enabling strict trusted policy.

## Preset comparison

| Preset | Intended use | Hard gates | Advisory gates | External prerequisites |
|--------|--------------|------------|----------------|------------------------|
| `minimal` | smallest local objective gate | syntax, invariant | none | language toolchain only when relevant |
| `java-ci` | balanced Java repository | syntax, invariant, dependency, design, test-adequacy | density, cohesion, duplication | none by default |
| `agent-paste-strict` | broad generated-change integrity | syntax, invariant, dependency, design, test-adequacy, security, package-existence | density, cohesion, duplication | package registry/mirror |
| `asf` | advisory community-project example | none | license-header, PR metadata, invariant, package-existence, test-adequacy, duplication | package registry/mirror |
| `airflow` | advisory framework-rule example | none | rules, external, invariant, tests, package, security, PR metadata, cohesion, duplication | package registry; ruff optional |

## Which one should I use?

- Choose **minimal** for first local adoption or an air-gapped start.
- Choose **java-ci** for a typical Java service/library, then add security/registry checks deliberately.
- Choose **agent-paste-strict** when generated changes are common and CI has registry access.
- Use **asf** and **airflow** as examples for their contribution/framework contexts, not as the product's identity.
- Create a custom explicit policy for production after calibration.

## Minimal

Only `syntax` and `invariant` are enabled. Every other gate is explicitly off. It checks parse failures and objective merge/paste/placeholder artifacts without registry/model/external-tool inputs.

## Java CI

Adds local import-manifest consistency, repository design rules, and test-adequacy. Density, cohesion, and duplication remain advisory. Registry, secret tripwire, docs/PR/header policy, external tools, rules packs, and Jev are off.

## Agent-paste strict

Adds `security` and `package-existence` to the Java-CI style objective checks. Package existence uses a registry/mirror, so `aiv verify` declares `package-registry` as an external input.

## ASF example

An advisory-only contribution profile. It demonstrates how a community can calibrate findings before making any gate blocking. It does not make AIV Apache-specific; all core modules and generic profiles are project-neutral.

## Airflow example

An advisory rule-pack profile combining generic AIV rules with optional ruff AIR SARIF. `ruff` is not bundled; missing tool behavior follows the external gate configuration. The same generic `rules` mechanism supports any project pack.

## Source and packaged copies

Authoritative source files live under `presets/<name>/config.yaml`. Matching copies ship inside the CLI under `aiv-cli/src/main/resources/presets/` and are checked by tests/build. When changing a preset, update both copies and run:

```bash
mvn -pl aiv-cli -am verify
```

For a full per-gate/profile decision matrix, see [docs/MODULE-ADOPTION-GUIDE.md](../docs/MODULE-ADOPTION-GUIDE.md).

**Author:** Viquar Khan
