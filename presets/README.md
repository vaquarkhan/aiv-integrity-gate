# Config presets

Starter `.aiv/config.yaml` packs. Apply with:

```bash
java -jar aiv-cli.jar init --preset agent-paste-strict
java -jar aiv-cli.jar init --preset java-ci
java -jar aiv-cli.jar init --preset minimal
```

| Preset | Use when |
|--------|----------|
| `agent-paste-strict` | Hard syntax / invariant / dependency / design / security; density + cohesion warn |
| `java-ci` | Java-heavy CI (density thresholds tuned; security on) |
| `minimal` | Syntax + invariant only |

Same files ship inside the CLI JAR under `/presets/` and are copied from this directory at build time.
