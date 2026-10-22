# Tutorial: handling AI-assisted pull requests in Apache Airflow with AIV

This tutorial shows how a project with Apache Airflow's contribution volume can use AIV to give maintainers deterministic evidence before they spend review time. It uses Airflow's own published process as the reference point and is written for a maintainer or release manager who wants to pilot AIV without changing the project's merge policy.

AIV is not an Apache project. Nothing here replaces Airflow's existing tooling (`prek`, ruff, mypy, Magpie triage, the open-PR limit). AIV adds one layer: evidence about the code in the diff, produced on the contributor's machine and in CI, before a human reads the PR.

**Author:** Viquar Khan

---

## 1. The problem Airflow documents

Airflow's contributing docs describe the situation directly: the project could promise a first review when it had around 100 open pull requests; it exceeded 1,000 with hundreds never reviewed. The maintainers' response is public:

- a limit of five open pull requests per contributor without write access, enforced through `.asf.yaml`;
- six quality criteria (descriptive title, imperative mood, meaningful body, passing static checks, Gen-AI disclosure, coherent changes), with non-compliant PRs converted to draft;
- a `Generated-by:` disclosure block in the PR template;
- two-stage triage: a deterministic first pass over CI status, conflicts, unresolved threads and staleness, then human review.

What the first stage does not check is the code itself. A PR can pass CI, have a tidy description and a disclosure line, and still contain a test that asserts nothing, a dependency that does not exist, a copied block, or a secret. Finding those by reading is the expensive part. That is the gap AIV fills.

## 2. What AIV adds, mapped to Airflow's criteria

| Airflow criterion or pain | AIV gate | Result type |
|---|---|---|
| Descriptive title, imperative mood, meaningful body | `pr-metadata` (`min_title_words`, template detection) | advisory in a pilot |
| Gen-AI disclosure present | `pr-metadata` with `require_disclosure: true` | advisory in a pilot |
| Coherent changes only | `cohesion` (`max_areas`) | advisory |
| Tests that prove nothing | `test-adequacy.assertion-free-test`, `invariant.placeholder-test` | hard |
| Production change without any test | `test-adequacy.missing-tests` | advisory |
| Invented or misspelled dependency in `pyproject.toml` or `requirements*.txt` | `package-existence` | hard |
| Leftover agent paste, conflict markers, provenance trailers inside source | `invariant` | hard |
| Credentials in the diff | `security` | hard |
| Copied blocks | `duplication` | advisory |
| DAGs that import cleanly and fail in the scheduler (`start_date=datetime.now()`, no `catchup=`, hollow `execute()`, `echo TODO` tasks, `example_*` ids, sensors without `timeout=`, imports removed in Airflow 3) | `rules` with the shipped `airflow` pack (`rules.airflow.*`); add your own checks in `.aiv/rules/*.yaml` with the same engine | advisory in a pilot |
| Ruff `AIR` rules, in the same receipt as everything above | `external` (runs `ruff check --select AIR`, reads SARIF) | advisory in a pilot |
| Other static checks | leave to `prek`, ruff and mypy; do not duplicate |

Rules Airflow already enforces elsewhere stay where they are. The one exception is ruff's `AIR` family: it is pulled into the AIV comment through the `external` gate so a reviewer sees one list, not two. AIV runs in seconds on the diff and its output is machine-readable, so the triage stage can consume it.

## 3. Pilot configuration

The shortest route is the shipped preset:

```bash
java -jar aiv-cli.jar init --workspace . --preset airflow
```

It writes the configuration below to `.aiv/config.yaml`. Start advisory. Promote a rule to blocking only after it has produced zero false positives on the last 90 days of merged pull requests. Commit the file on `main`; AIV reads it from the base ref, so a PR cannot loosen it for itself.

```yaml
schema_version: 1
fail_fast: false
exclude_paths:
  - "docs/**"
  - "*.md"
  - "chart/**"
  - "generated/**"

# Publication: label and comment carry evidence, never an authorship claim.
ai_slop_pr_label: AI-SLOP
ai_slop_pr_comment: true
ai_slop_label_gates: [rules, invariant, security, package-existence, test-adequacy, external]

gates:
  # Generic rule engine loading the shipped "airflow" rule pack (rules.airflow.*).
  - id: rules
    enabled: true
    severity: warn
    config:
      packs: [airflow]
      rules_files: [".aiv/rules/*.yaml"]
      params:
        example_dag_id_pattern: "^(example|test|my|sample|demo)_"
        # known_conn_ids: [aws_default, google_cloud_default]
  - id: external
    enabled: true
    severity: warn
    config:
      tools:
        - id: ruff-airflow
          command: ["ruff", "check", "--select", "AIR", "--output-format", "sarif", "--exit-zero", "{files}"]
          sarif: stdout
          severity: warn
          file_extensions: [.py]
          on_missing: skip
  - id: invariant
    enabled: true
    severity: warn
    config:
      placeholder_words: [TBD, FIXME, XXX]
  - id: test-adequacy
    enabled: true
    severity: warn
    config:
      check_assertions: true
      require_tests: true
  - id: package-existence
    enabled: true
    severity: warn
    config:
      allow: [apache-airflow, apache-airflow-providers-common-sql]
  - id: security
    enabled: true
    severity: warn
    config:
      allow_patterns: ["tests/.*/fixtures/"]
  - id: pr-metadata
    enabled: true
    severity: warn
    config:
      min_title_words: 3
      require_disclosure: false
  - id: cohesion
    enabled: true
    severity: warn
    config:
      max_areas: 3
      area_depth: 2
  - id: duplication
    enabled: true
    severity: warn
  # Covered by prek/ruff/mypy in Airflow CI; keep off to avoid duplicate signals.
  - id: syntax
    enabled: false
  - id: dependency
    enabled: false
  - id: design
    enabled: false
  - id: density
    enabled: false
  - id: license-header
    enabled: false
  - id: doc-integrity
    enabled: false
```

Why these choices:

- `severity: warn` everywhere during the pilot. The exit code stays 0, the evidence still reaches the PR.
- `package-existence.allow` lists first-party distributions so provider PRs do not trigger registry lookups for names the project owns.
- `syntax` and `dependency` are off because Airflow's own static checks cover them better for Python.
- `rules` is a generic engine; nothing in the jar is Airflow-specific except the `airflow` rule pack it loads. `known_conn_ids` is commented out: fill it from `airflow connections list` on your deployment and the `unknown-conn-id` rule starts reporting typos in `conn_id=`. Your own checks go in `.aiv/rules/*.yaml` (schema in [DEVELOPER-CONFIGURATION.md](DEVELOPER-CONFIGURATION.md), section `rules`); for example a `call` rule that requires `retries=` on every `PythonOperator(`, or a `pattern` rule banning `schedule_interval=` in favour of `schedule=`.
- `external` runs ruff with `--exit-zero` so the verdict comes from the SARIF results, not from ruff's exit code. `on_missing: skip` means a contributor without ruff installed sees `SKIPPED ruff-airflow` in the summary instead of a failure; CI has ruff, so the backstop still runs it.

What this looks like on a flawed DAG (`dags/hello.py` with `start_date=datetime.now()`, no `catchup`, an `echo TODO` task, a sensor without `timeout`, and a `requirements.txt` naming a package that does not exist on PyPI):

```text
rules: passed=False blocks=False
    rules.airflow.start-date-now    dags/hello.py:7
    rules.airflow.missing-catchup   dags/hello.py:7
    rules.airflow.example-dag-id    dags/hello.py:7
    rules.airflow.placeholder-task  dags/hello.py:8
    rules.airflow.removed-import    dags/hello.py:5
    rules.airflow.sensor-no-timeout dags/hello.py:9
external: passed=False blocks=False :: ruff-airflow: 4 findings
    external.ruff-airflow.AIR311    dags/hello.py:7
    external.ruff-airflow.AIR001    dags/hello.py:8
    external.ruff-airflow.AIR312    dags/hello.py:8
package-existence: passed=False blocks=False
    package-existence.unknown-package requirements.txt:2
```

Exit code 0 because the preset is advisory; the same findings land in the PR comment and on the `AI-SLOP` label when the publishers are enabled.

## 4. Wire it as a `prek` hook (contributor machine)

Airflow uses `prek` as its pre-commit runner. Add AIV so contributors see the evidence before they push:

```yaml
# .pre-commit-config.yaml (excerpt)
repos:
  - repo: https://github.com/vaquarkhan/aiv-integrity-gate
    rev: v2.0
    hooks:
      - id: aiv-gate
```

A contributor who runs `prek run --from-ref main` now sees, for example:

```text
test-adequacy: FAIL - tests/providers/x/test_hook.py:41 Test added without any assertion/expectation/verification: 'def test_connection(self):'
package-existence: FAIL - pyproject.toml:118 Dependency 'ghost-sdk' does not exist in the pypi registry
```

They fix it locally. The maintainer never sees that version of the PR.

## 5. Wire it in GitHub Actions (CI backstop)

```yaml
name: AIV evidence
on:
  pull_request:
    types: [opened, synchronize, reopened]
permissions:
  contents: read
  pull-requests: write
jobs:
  aiv:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5
        with:
          fetch-depth: 0
      - uses: vaquarkhan/aiv-integrity-gate@main
        with:
          base-ref: origin/${{ github.base_ref }}
          cli-jar-url: https://artifacts.example/aiv/aiv-cli-compatible.jar
          label-ai-slop: 'true'
          comment-ai-slop: 'true'
```

What happens on each push:

1. AIV pins `origin/main` to one commit, materializes its complete `.aiv/` policy tree, and rejects policy drift in the hardened Action.
2. Gates run on the diff. In the pilot every finding is advisory, so CI stays green.
3. If any selected finding exists, the PR gets the `AI-SLOP` label and one comment listing the findings. The comment is updated in place on later pushes and deleted when the findings are gone.
4. The JSON report (`--output-json`) can be uploaded as an artifact for the triage stage.

The label name is a project choice. Some projects prefer `needs-evidence` to avoid any implication about authorship; the comment body always says the findings are deterministic and do not determine who wrote the change.

## 6. Feed the triage stage

Airflow's triage skill runs deterministic checks and proposes a disposition. AIV's JSON report has the shape it needs:

```json
{
  "passed": true,
  "gates": [
    {"id": "test-adequacy", "passed": false, "blocks_ci": false,
     "findings": [{"rule_id": "test-adequacy.assertion-free-test", "file": "tests/x/test_hook.py", "start_line": 41, "message": "..."}]}
  ],
  "notices": ["[CONFIG-CHANGE] .aiv/config.yaml"]
}
```

A triage rule such as "convert to draft when `test-adequacy.assertion-free-test` or `package-existence.unknown-package` is present and the author has no write access" is deterministic, explainable to the contributor, and reversible by fixing the code.

## 7. Calibrate before blocking

Before any gate becomes `severity: fail`:

1. Check out the last 90 days of merged PRs (these are the "known good" set).
2. Run `java -jar aiv-cli.jar --workspace . --diff <merge-base> --head <merge-commit> --output-json out/<pr>.json` for each.
3. Count findings per rule. Any rule that fires on merged PRs is either a real latent defect or a false positive; inspect each one.
4. Promote only rules with zero false positives. Keep the rest advisory.

The public `aiv-airflow-bench` repository and `benchmarks/airflow-bench-compare/` in this repository show this procedure end to end, including two false positives that were found and fixed this way (ruff rule codes read as package names, and a multi-line `pytest.mark.parametrize` signature ending a test body early).

## 8. What reviewers save

With the pilot in place, a maintainer opening a PR sees in the first screen:

- whether the tests added by the PR assert anything;
- whether every new dependency exists;
- whether the PR carries paste artifacts, conflict markers or secrets;
- whether the PR touches more areas than the project allows;
- whether the PR tried to change the gate configuration or skip the gate.

Each item links to a file and line. The maintainer still reads the code for design and correctness; AIV removes the mechanical checks from that reading and documents them.

## 9. What AIV does not do for Airflow

- It does not decide whether a contributor used an AI tool. Disclosure is the contributor's responsibility and is checked only for presence when `require_disclosure` is on.
- It does not run the test suite, type-check, or execute contracts. Airflow CI does that.
- It does not prove that code which compiles and passes tests is semantically correct. No diff-scoped tool can.
- It does not replace the open-PR limit, Magpie triage, or human review.

## 10. Checklist

- [ ] `.aiv/config.yaml` committed on `main` with every gate advisory
- [ ] `prek` hook added so contributors run AIV locally
- [ ] Action added with `pull-requests: write` and the label and comment enabled
- [ ] 90-day merged-PR calibration run recorded, with per-rule counts
- [ ] Rules with zero false positives promoted to `fail`, one at a time
- [ ] Triage rules updated to read the JSON report
