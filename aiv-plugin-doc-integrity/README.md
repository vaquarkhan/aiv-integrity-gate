# Module: `aiv-plugin-doc-integrity` (gate `doc-integrity`)

`doc-integrity` validates changed documentation as an executable product surface. It catches broken local links, fabricated paths, incomplete commands, missing required terms, and inconsistent cross-references before users follow bad instructions.

## Architecture role

The gate consumes changed text-like files plus repository state through `AIVContext`. It returns one blocking `GateResult` with stable findings when configured documentation invariants fail. The gate is default-off and can run automatically only when a diff touches documentation.

## Runtime flow

1. Select `.md`, `.txt`, `.rst`, and recognized project instruction files.
2. Load project documentation rules.
3. Validate local Markdown links and referenced files.
4. Evaluate declared cross-reference pairs.
5. Check required mentions for selected paths.
6. Validate canonical command completeness.
7. detect path-shaped references that do not exist.
8. Aggregate findings with rule ids and messages.

HTTP URLs are not fetched. Remote availability is a separate network concern; this gate stays local.

## Components

| Class | Responsibility |
|-------|----------------|
| `DocIntegrityGate` | orchestration and result aggregation |
| `DocRulesLoader` / `DocRules` | parse declarative documentation policy |
| `MarkdownLinkChecker` | local links, anchors, images |
| `FileExistenceValidator` | declared required paths |
| `CrossReferenceChecker` | paired/related docs |
| `RequiredMentionValidator` | required terms by path |
| `CommandCompletenessChecker` | complete canonical commands |
| `PathFabricationDetector` | path-like references that do not exist |
| `DocPathUtils` / `DocFileExtensions` | path and file selection helpers |

## Enablement

```yaml
gates:
  - id: doc-integrity
    enabled: true
    severity: fail
    config:
      auto: true
```

`auto: true` means the core runs the gate only when filtered changed files include docs. `--include-doc-checks` forces it for a CLI run.

Project-specific rules are described in [DEVELOPER-CONFIGURATION.md](../docs/DEVELOPER-CONFIGURATION.md). Keep policy files under `.aiv/` so the CLI snapshots and protects them with other gate policy.

## Finding classes

Stable rule names describe the failing validator, such as broken link, missing path, cross-reference, required mention, incomplete command, or fabricated path. Findings currently use the relevant documentation path; some validators anchor at line 1 when exact source location is unavailable.

## Example workflow

```bash
java -jar aiv-cli.jar \
  --workspace . --diff origin/main --head HEAD \
  --require-trusted-config \
  --include-doc-checks \
  --output-sarif aiv-docs.sarif
```

Use this gate for READMEs, tutorials, runbooks, architecture references, command snippets, and generated product docs. It complements a Markdown linter: lint checks formatting; doc-integrity checks whether referenced product facts exist locally.

## Failure behavior

- A confirmed local inconsistency blocks when severity is `fail`.
- `severity: warn` converts the result to advisory through `aiv-core`.
- Missing/malformed configured policy is an input/configuration error, not a clean pass.
- File-walk/read uncertainty follows precision-first behavior and should be visible in the surrounding run.

## Trust and safety

Documentation content is untrusted data. The gate parses references and text but never executes commands found in docs. Relative paths are normalized against the workspace. Policy is consumed from the immutable policy workspace established by the CLI.

## Extension points

Add a focused validator class, return deterministic findings, wire it through `DocIntegrityGate`, document the rule id, and add tests. Do not fetch arbitrary URLs or execute copied command snippets in this module.

## Tests and build

Every component has a dedicated test class: gate orchestration, rules loading, links, existence, cross-references, required mentions, command completeness, fabricated paths, extensions, and path utilities.

```bash
mvn -pl aiv-plugin-doc-integrity test
mvn -pl aiv-plugin-doc-integrity verify
```

## Interactions and boundaries

The gate emits standard findings consumed by JSON, SARIF, GitHub Checks, baselines, and suppressions. It proves local documentation consistency, not factual truth of remote content or whether a procedure is operationally safe in a live environment.
