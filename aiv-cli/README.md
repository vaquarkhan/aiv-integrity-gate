# Module: `aiv-cli`

**Role:** **Command-line entry point**—packages a runnable `Main` class and wires `GitDiffProvider`, `YamlConfigProvider`, optional doc-check wrapper, `StdoutReportPublisher`, and `Orchestrator`.

## Packaging

The build uses **Maven Shade** to produce a **single uber JAR** suitable for:

```bash
java -jar aiv-cli-*.jar --workspace . --diff origin/main
```

The version string printed by **`--version`** / **`-V`** comes from filtered **`META-INF/aiv-cli.properties`** at build time.

## Typical arguments

| Argument | Description |
|----------|-------------|
| `--workspace` | Repository root (default: current directory). |
| `--diff` | Base ref for the diff. |
| `--head` | Head ref (default `HEAD`). |
| `--include-doc-checks` | Enable doc-integrity for this invocation. |
| `--version`, `-V` | Print version and exit. |

Exit codes: **0** = pass, **1** = failure or fatal error.

## See also

- [`docs/TUTORIAL.md`](../docs/TUTORIAL.md) — end-to-end usage
- [`aiv-adapter-git`](../aiv-adapter-git/README.md) — how diffs are produced

**Author:** Vaquar Khan
