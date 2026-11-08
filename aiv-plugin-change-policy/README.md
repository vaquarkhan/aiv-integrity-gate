# Module: `aiv-plugin-change-policy` (gate `change-policy`)

The change policy gate lets a project say, in its pinned AIV config, what a reviewable pull request
looks like: a size budget by author tier and area, tests for source changes, a title that matches the
diff, a description that does not overclaim tests, and template and history rules. It computes
deterministic facts about the change and evaluates declarative policies over them. Design and evidence:
[CHANGE-POLICY-ENGINE-DESIGN.md](../docs/AI-integration/CHANGE-POLICY-ENGINE-DESIGN.md) and
[MAINTAINER-PAIN-AND-PR-PATTERNS-ANALYSIS.md](../docs/AI-integration/MAINTAINER-PAIN-AND-PR-PATTERNS-ANALYSIS.md).

## Architecture role

Default-off gate (listed in `DEFAULT_OFF_GATES`). It reads the diff in `AIVContext` (including files
that `exclude_paths` removed, because they still cost review time), PR metadata from the CI
environment, and read-only `git` history. It never calls a model and never writes anything.

| Class | Role |
|---|---|
| `Classifier`, `Glob` | one category and one area per path; strict globs with no substring fallback |
| `DiffStats` | added and removed lines per file from the unified diff |
| `MetaText` | intent, linked issue, template sections, test claims, disclosure consistency |
| `GitHistory` | `git rev-list` and `git log`; any failure makes the fact unknown |
| `FactCollector` | builds the fact map, including `budget.exceeded` |
| `Condition` | parses and evaluates `all` / `any` / `not` / leaf conditions, three-valued |
| `PolicyConfig` | loads the built-in pack and overlays the project config, with validation |
| `ChangePolicyGate` | evaluates policies, overrides, rule actions, findings |

## Runtime flow

1. Load the pack (`oss-maintainer` by default) and overlay the project config. Invalid config is exit 2.
2. Read PR metadata: `AIV_PR_TITLE`, `AIV_PR_BODY`, `AIV_PR_LABELS` (JSON array),
   `AIV_PR_AUTHOR_ASSOCIATION`, `AIV_PR_DRAFT`. The composite action exports all five.
3. Collect facts. Missing inputs leave facts unknown (for example, no PR metadata in a local run).
4. Evaluate each policy. TRUE fires, FALSE passes, UNKNOWN skips and is listed in the gate message.
5. A fired policy with a matching override label becomes a `change-policy.override` notice.
6. Any fired `fail` policy fails the gate; otherwise fired `warn` policies make it advisory.

## Configuration

```yaml
gates:
  - id: change-policy
    enabled: true
    config:
      pack: airflow                  # oss-maintainer (default), airflow, strict-agent, none
      budgets:
        default: { files: 50, lines: 1500 }
        external: { files: 30, lines: 800 }
        external.core: { non_test_lines: 200 }
      template: { required_sections: ["Why", "How was this tested"] }
      overrides:
        labels: [large-change-approved]                     # applies to overridable policies
        per_policy: { untested-source-change: [tests-not-applicable] }
      policies:
        - { id: untested-source-change, action: fail }      # partial override of a pack policy
        - id: no-docs-only-bumps
          when: { all: [ { fact: intent.class, eq: bump }, { fact: lines.docs_changed, gt: 0 } ] }
          action: warn
          message: "Bump PR changes {lines.docs_changed} docs lines."
      emit_facts: false              # true adds a change-policy.facts note with the JSON snapshot
```

| Key | Meaning |
|---|---|
| `pack` | built-in pack to start from |
| `classification`, `areas`, `area_depth` | extra globs per category; named areas; fallback depth (default 1) |
| `non_reviewable_categories` | default `lockfile`, `generated`, `vendored`, `binary` |
| `budgets` | keys `default`, `<tier>`, `<area>`, `<tier>.<area>`; limits `files`, `lines`, `non_test_lines` |
| `author_tiers` | tier name to GitHub associations; unlisted associations are `external` |
| `template` | `required_sections`, `ignore_patterns` (regexes for boilerplate lines) |
| `linked_issue_patterns` | extra regexes, for example `AIRFLOW-\d+`; `#123` and issue URLs always count |
| `overrides` | project-wide `labels`; `per_policy` labels |
| `policies` | merged by `id` with the pack, field by field |
| `params` | values referenced as `${name}` in conditions |
| `git_facts`, `git_timeout_ms` | history facts on (default) and the per-command timeout |

Facts and operators are listed in the design doc section 4. Unknown facts or operators are errors.

## Built-in policies

`oss-maintainer` ships 14 policies. All are `warn` except `mega-diff` (1,000+ changed files, `fail`,
overridable), which never flagged a merged PR in the Airflow calibration. Size and shape policies:
`mega-diff`, `over-budget`, `small-fix-huge-diff` (small-change title, 5,000+ total lines),
`small-intent-large-diff`, `untested-source-change`, `large-new-code-unit` (new 500+ line file, 150+
line function or stub bodies). The `airflow` pack adds `commit-hygiene`; `strict-agent` makes the size
and test policies blocking.

## Findings

- `policy.<id>`: file `(diff)`, message prefixed `[WARN]` or `[FAIL]`, rendered from the policy message.
- `change-policy.override`: `[POLICY-OVERRIDE] <id> by label <label>`.
- `change-policy.facts`: only with `emit_facts: true`.

`rule_actions` (for example `policy.formatting-churn: off`) and `unsuppressible_rules` apply as for any gate.

## Interactions

- The composite action (`action.yml`) exports the PR metadata variables.
- Labels and fix briefs use the normal AIV outputs; the gate adds no new comment of its own.
- Magpie and the review ledger can read `change-policy.facts` from the JSON report.

## Failure behavior

Configuration errors raise `IllegalArgumentException` (exit 2). Git failures, shallow clones and
missing metadata never fail the gate; the affected policies are skipped and named in the message.

## Extension points

New facts go in `FactCollector` and `Facts.NAMES`; new packs are YAML files under
`src/main/resources/io/github/vaquarkhan/aiv/plugin/changepolicy/packs/` and may `extends` another pack.

## Tests and build

Unit tests cover globs, diff parsing, classification, text facts, the condition language, pack merging,
config validation, every pack policy, overrides, rule actions, history facts against a real temporary
repository, and git failure paths. `CorpusCalibrationRunner` (test scope) runs the gate on stored corpus
PRs for `benchmarks/airflow/scripts/run-change-policy-calibration.py`.

```bash
mvn -pl aiv-plugin-change-policy verify
python benchmarks/airflow/scripts/run-change-policy-calibration.py
```

## Boundary

Policies describe changes, not authors. A fired policy says a PR is expensive or inconsistent to
review, never that it was AI-written.
