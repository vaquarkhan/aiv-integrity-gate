# Module: `aiv-plugin-syntax` (gate `syntax`)

The syntax gate answers the earliest objective question about a changed file: can the named language parser read it? Confirmed parse failures block before a long build starts. Unavailable parsers are visible and can be promoted to fail-closed behavior.

## Architecture role

`SyntaxGate` is a default-on `QualityGate`. It dispatches each changed file to an in-process parser or a bounded local toolchain and returns `syntax.parse` or `syntax.unverified` findings.

```text
ChangedFile -> extension dispatch -> parser/tool -> ValidationResult -> GateResult
```

## Supported inputs

| Files | Validator | External process |
|-------|-----------|------------------|
| `.java` | JavaParser at Java 17 language level | no |
| `.yaml`, `.yml` | SnakeYAML safe constructor | no |
| ordinary `.json` | strict RFC 8259 `JsonLite` | no |
| `.py` | configured Python `ast.parse` | yes |
| `.js`, `.mjs`, `.cjs` | `node --check` | yes |
| `.ts`, `.mts` | Node experimental strip-types + check when supported | yes |
| `.go` | `gofmt` on stdin | yes |

`tsconfig*.json`, JSX/TSX without a transform, templated YAML/JSON, and hash-prefixed Java-like files produce `syntax.unverified` rather than a false parse claim.

## Strict JSON

Ordinary JSON uses the shared RFC 8259 parser. It rejects unquoted keys, comments, YAML tags/scalars, trailing commas, raw control characters, leading plus/zero number forms, missing fraction/exponent digits, non-JSON whitespace, and non-ASCII number digits.

## External process safety

`ProcessCommandExecutor`:

- defaults to a 30-second timeout (`-Daiv.syntax.timeout.seconds=N`),
- writes source through stdin or a temporary file as required,
- redirects/caps diagnostics at 1 MiB,
- kills the process tree on timeout,
- reports missing/crashed tools as unverified,
- performs Windows-safe best-effort cleanup.

Tool commands can be overridden for portable installations/tests:

```bash
java -Daiv.python.command=python3 -Daiv.node.command=node \
     -Daiv.gofmt.command=gofmt -jar aiv-cli.jar --diff origin/main
```

## Configuration

```yaml
gates:
  - id: syntax
    enabled: true
    severity: fail
    config:
      strict_toolchains: true
```

| Key | Default | Meaning |
|-----|---------|---------|
| `strict_toolchains` | `false` | make every `syntax.unverified` finding blocking |

Without strict mode, unverified files remain in the passing gate result as structured findings. JSON reports retain them and SARIF emits them at level `note`. With strict mode the same findings block.

## Rule IDs

| Rule | Meaning |
|------|---------|
| `syntax.parse` | a parser/tool confirmed invalid syntax |
| `syntax.unverified` | parser/transform/tool unavailable for that file |

## Examples

```json
{"a": [1, true, null]}
```

passes. This blocks:

```text
{foo: bar}
```

A `.tsx` file without a configured transform returns `syntax.unverified`; it is never described as parsed.

## Interactions

Syntax should run before semantic/static analyzers. `external` can add compiler/framework-specific checks. `rules` handles project patterns. The gate consumes file content from the diff, not files from the policy workspace.

## Extension points

Implement `SourceValidator` for another language and return `ValidationResult.ok`, `fail(line, detail)`, or `skipped(reason)`. Use `CommandExecutor` for processes so timeout/cap semantics stay consistent. Add extension dispatch and exhaustive tests.

## Tests and build

- `SyntaxGateTest`: formats, strict JSON, unverified/strict behavior, locations.
- Python/Node/Go validator tests: commands, failures, unsupported modes.
- `ProcessCommandExecutorTest`: missing executable, timeout, process kill, diagnostics cap, cleanup.

```bash
mvn -pl aiv-plugin-syntax test
mvn -pl aiv-plugin-syntax verify
```

## Boundary

This gate proves parse validity for supported parsers. It does not prove compilation, type correctness, imports, runtime behavior, or semantic correctness. Pair it with build/type tools through `external` and the remaining AIV gates.
