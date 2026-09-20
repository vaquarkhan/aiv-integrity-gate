# Minimal PASS/FAIL fixture pack

Tiny local fixtures. The **public** live demo is already
[vaquarkhan/aiv-airflow-bench](https://github.com/vaquarkhan/aiv-airflow-bench)
(Actions on open PRs + [Pages report](https://vaquarkhan.github.io/aiv-airflow-bench/)).

Use this folder only when you want a two-file smoke check without cloning the bench.

## Layout

| Path | Intent |
|------|--------|
| `.aiv/config.yaml` | Strict-ish demo config (syntax + invariant + security) |
| `pass/CleanService.java` | Clean added file — should pass |
| `fail/BrokenPaste.java` | Conflict marker + AI elision — should fail invariant |
| `fail/leaked.env` | Fake AWS key shape — should fail security when enabled |

## Local smoke

```bash
# from a throwaway git repo after copying these files and committing a fail/ change:
java -jar aiv-cli.jar --diff HEAD~1 --head HEAD
# or
./scripts/aiv.sh --diff HEAD~1 --head HEAD
```

For labeled corpus PRs and published metrics, use the [airflow bench](https://github.com/vaquarkhan/aiv-airflow-bench) instead.
