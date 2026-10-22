# Gate map

[Hello World](HELLO-WORLD.md) runs all 16 gates in one lab. Use this page to open a single gate. Configuration keys are in [DEVELOPER-CONFIGURATION.md](DEVELOPER-CONFIGURATION.md).

A gate that is **on** runs when `.aiv/config.yaml` does not mention it. A gate that is **off** runs only when the config enables it.

| Gate | Default | What a user checks | Lab |
|------|---------|--------------------|-----|
| `syntax` | on | Changed source parses. Missing toolchains are reported, not treated as success. | [syntax](HELLO-WORLD.md#7-gate-1-syntax) |
| `invariant` | on | Conflict markers, placeholders, pasted edit instructions, and provenance trailers in source. | [invariant](HELLO-WORLD.md#8-gate-2-invariant) |
| `dependency` | on | Java and Python imports match the local manifest, standard library, or first-party package. | [dependency](HELLO-WORLD.md#9-gate-3-dependency) |
| `package-existence` | on | A newly declared package is found, absent, or unverified at a registry. | [package-existence](HELLO-WORLD.md#10-gate-4-package-existence) |
| `test-adequacy` | on | An added test with no assertion blocks. A production change with no test is advisory. | [test-adequacy](HELLO-WORLD.md#11-gate-5-test-adequacy) |
| `security` | off | High-confidence credentials and private-key headers on changed lines. | [security](HELLO-WORLD.md#12-gate-6-security) |
| `design` | on | Repository rules for forbidden and required calls. | [design](HELLO-WORLD.md#13-gate-7-design) |
| `rules` | off | Shipped or project YAML rules. The Airflow pack is `packs: [airflow]`. An empty `rules_files` glob is skipped. | [rules](HELLO-WORLD.md#14-gate-8-rules) |
| `density` | on | Low-signal or oversized changes. Keep this advisory until the repository has a baseline. | [density](HELLO-WORLD.md#15-gate-9-density) |
| `cohesion` | on | One change touches too many unrelated areas. | [cohesion](HELLO-WORLD.md#16-gate-10-cohesion) |
| `duplication` | on | Copied blocks across the change. Advisory in the shipped presets. | [duplication](HELLO-WORLD.md#17-gate-11-duplication) |
| `external` | off | A trusted local tool, with SARIF folded into the same receipt. | [external](HELLO-WORLD.md#18-gate-12-external) |
| `doc-integrity` | off | Local links, paths, and commands named by the docs rules. | [doc-integrity](HELLO-WORLD.md#19-gate-13-doc-integrity) |
| `pr-metadata` | off | Pull request title, body, and disclosure fields the project requires. | [pr-metadata](HELLO-WORLD.md#20-gate-14-pr-metadata) |
| `license-header` | off | Required header text on new source files. | [license-header](HELLO-WORLD.md#21-gate-15-license-header) |
| `jev` | off | Optional model questions. The result is always advisory. | [jev](HELLO-WORLD.md#22-gate-16-jev) |

After the gate lab, Hello World continues with the JSON and SARIF report, the trusted-policy check, `aiv verify`, and the audit chain.
