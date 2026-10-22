# Configuration reference

This is the complete reference for `.aiv/config.yaml`, the rule files it points to, the CLI flags, and the environment variables. Every gate is configured through the same shape, so you can tune AIV to your repository without changing code.

**Author:** Viquar Khan

---

## 1. File layout

```text
your-project/
  .aiv/
    config.yaml          # which gates run, their settings, severity, publication
    design-rules.yaml    # forbidden / required patterns (design gate)
    doc-rules.yaml       # documentation rules (doc-integrity gate)
    baseline.txt         # optional known findings (rule_id|file per line)
    rules/
      team.yaml          # generic project/framework rule packs
  .github/workflows/aiv.yml
```

Where policy is read from matters. For a real Git diff AIV pins the base ref once to a commit OID, reads configuration from that commit, and materializes the complete `.aiv/` tree into a private immutable snapshot. Design rules, generic rules, documentation rules, and baselines consume the same frozen bytes. Workspace and `--diff-json` runs copy `.aiv/` once for internal consistency. `--require-trusted-config` refuses missing base policy, ignored/untracked extras, symlinks, path escapes, and any live-tree difference. Non-strict fallback emits `[POLICY-FALLBACK]`; `--config-from-workspace` is an explicit opt-out.

---

## 2. Shape of a gate entry

```yaml
schema_version: 1
gates:
  - id: <gate-id>          # one of the ids in section 4
    enabled: true          # false disables the gate entirely
    severity: fail         # fail (default) blocks CI; warn reports only
    config:                # gate-specific keys, see section 4
      key: value
```

Rules that apply to every gate:

- A gate absent from a partial list is enabled with defaults except `doc-integrity`, `security`, `license-header`, `pr-metadata`, `external`, `rules`, and `jev`, which are default-off in the orchestrator. The no-config/generated default policy is a separate explicit configuration and enables additional hard/advisory gates. For predictable production behavior, list all 16 gate ids or start from a shipped explicit preset.
- `severity: warn` turns a failed result into an advisory result. Advisory results are printed, written to JSON and SARIF, can trigger a PR label, and never change the exit code. Any other value behaves as `fail`.
- `config` is a free map. Unknown keys are ignored. Wrong types fall back to the default for that key, except regex lists, which fail the run with a clear message when a pattern does not compile.
- Two gates return mixed results on their own: `test-adequacy` returns `missing-tests` findings as advisory even when the gate severity is `fail`, and `package-existence` returns `UNVERIFIED` findings as a passing result unless `strict: true`.

Shared keys accepted by the gates that scan source files (`density`, `design`, `invariant`, `duplication`, `license-header`):

| Key | Type | Meaning |
|---|---|---|
| `file_extensions` | list | Extensions to scan, with or without the leading dot. Replaces the gate default. |
| `languages` | list | Alternative to `file_extensions`: `java`, `kotlin`, `python`, `go`, `rust`, `scala`, `javascript`, `typescript`, `c`, `cpp`, `ruby`, `shell`. |

---

## 3. Global keys (top level of `config.yaml`)

| Key | Type | Default | Meaning |
|---|---|---|---|
| `schema_version` | integer | `1` | Must be `1`. |
| `exclude_paths` | list of globs | empty | Repository-relative globs removed from every gate. `**` is supported; negation is not. |
| `fail_fast` | boolean | `false` | Stop at the first blocking gate instead of collecting all findings. |
| `baseline` | string | none | Path to a baseline file. Findings listed as `rule_id|file` are ignored. |
| `skip_allowlist` | list of emails | empty | Authors allowed to use `/aiv skip` in the latest commit message. **Empty means the directive is ignored.** The commit must also carry a good signature (`git log --format=%G?` prints `G`). |
| `advisory_pr_label` | string | `aiv:advisory` | Label applied by `--label-pr-on-advisory` when advisory findings exist. |
| `advisory_label_gates` | list | `design, invariant, density, cohesion` | Gates whose advisory findings trigger `advisory_pr_label`. |
| `ai_slop_pr_label` | string | none | When set, AIV applies this label (for example `AI-SLOP`) to the pull request whenever the selected deterministic findings exist, and removes it when they do not. The label states that configured evidence needs review. It does not claim AI authorship. |
| `ai_slop_pr_comment` | boolean | `false` | When true, AIV creates or updates one PR comment listing the selected findings with rule, file, line, severity and a fixed remediation hint, and deletes the comment when no findings remain. |
| `ai_slop_label_gates` | list | empty (all failed gates) | Gate ids whose findings qualify for the label and comment. |
| `ai_slop_label_rules` | list | empty (all rules) | Rule ids or families (`security.*`) that qualify. Combined with `ai_slop_label_gates` using AND. |
| `audit_log` | string | none | Append every run to this hash-chained, tamper-evident audit log (relative to the workspace). Read from the base ref like every other key, so a PR cannot redirect or disable its own record. `--audit-log` and `AIV_AUDIT_LOG` override it. See [EVIDENCE-AND-PROPOSALS.md](EVIDENCE-AND-PROPOSALS.md). |

Example of a focused publication policy:

```yaml
ai_slop_pr_label: AI-SLOP
ai_slop_pr_comment: true
ai_slop_label_gates: [invariant, security, package-existence, test-adequacy]
ai_slop_label_rules:
  - invariant.*
  - security.*
  - package-existence.unknown-package
  - test-adequacy.assertion-free-test
```

---

## 4. Gate reference

### `syntax` (hard)

Parses changed files. Java, YAML and JSON are parsed in process. Python, JavaScript,
TypeScript and Go use bounded local toolchains. Missing tools, unsupported transforms, templated
YAML, `tsconfig*.json`, and JSX/TSX without a transform are reported as `syntax.unverified`.

| Key | Default | Meaning |
|---|---|---|
| `strict_toolchains` | `false` | When `true`, any unverified external/transform syntax result blocks. |

Interpreter commands can be overridden with JVM properties such as
`-Daiv.python.command=python3`, `-Daiv.node.command=node`, and
`-Daiv.gofmt.command=gofmt`. External validators time out after 30 seconds by default; override
with `-Daiv.syntax.timeout.seconds=N`.

Rules: `syntax.parse`, `syntax.unverified`.

### `invariant` (hard)

Markers that should never reach mainline. All checks except merge conflicts run on **added lines only** when a unified diff is available.

| Key | Default | Meaning |
|---|---|---|
| `placeholder_words` | `[TBD, FIXME, XXX]` | Words treated as placeholders. Replaces the default list. |
| `disabled_rules` | empty | Rule suffixes to skip: `merge-conflict`, `placeholder`, `ai-edit-artifact`, `ai-provenance`, `placeholder-test`. |
| `extra_artifact_patterns` | empty | Case-insensitive regexes checked on added lines of code files. Findings use rule `invariant.custom-artifact`. |
| `file_extensions` / `languages` | built-in code list | Which files count as code for artifact checks. |

Rules: `invariant.merge-conflict`, `invariant.placeholder`, `invariant.ai-edit-artifact`, `invariant.custom-artifact`, `invariant.ai-provenance`, `invariant.placeholder-test`.

### `test-adequacy` (hard for assertion-free tests, advisory for missing tests)

| Key | Default | Meaning |
|---|---|---|
| `check_assertions` | `true` | Flag test functions added by the change whose body contains no assertion, expectation, verification or exception check. Java, Kotlin, Scala, Python, JavaScript, TypeScript, Go. |
| `require_tests` | `true` | Flag production source files changed with no test file in the diff and no conventionally named test in the workspace (`FooTest`, `test_foo`, `foo.spec`, `foo_test`, and similar). Always advisory. |
| `max_workspace_files` | `20000` | Upper bound for the workspace test-file scan. |

Rules: `test-adequacy.assertion-free-test`, `test-adequacy.missing-tests`.

### `package-existence` (hard)

Every dependency newly declared in `requirements*.txt`, `pyproject.toml` (dependency arrays only), `package.json` (`dependencies`, `devDependencies`) and `pom.xml` must exist in its registry.

| Key | Default | Meaning |
|---|---|---|
| `strict` | `false` | Treat an unreachable registry (`UNVERIFIED`) as a failure. |
| `allow` | empty | Package names never checked (internal or private packages). |
| `pypi_url` | `https://pypi.org/simple/` | PyPI simple index base. Point at a mirror in air-gapped CI. |
| `npm_url` | `https://registry.npmjs.org/` | npm registry base. |
| `maven_url` | `https://repo1.maven.org/maven2/` | Maven repository base. |
| `timeout_seconds` | `5` | Per-request timeout. |

Rules: `package-existence.unknown-package` (hard), `package-existence.unverified` (reported, passing unless `strict`).

### `dependency` (hard)

Java and Python imports must be backed by the root `pom.xml` or `requirements.txt` / `pyproject.toml`. Python standard library and first-party packages are allowed automatically.

| Key | Default | Meaning |
|---|---|---|
| `whitelist` | empty | Additional allowed import prefixes. |

### `security` (hard, default on)

Secret patterns on added lines.

| Key | Default | Meaning |
|---|---|---|
| `entropy_threshold` | `4.5` | Minimum Shannon entropy (bits per character) for keyword-bound password or secret values. |
| `disabled_rules` | empty | Rule suffixes to skip, for example `high-entropy-secret`. |
| `allow_patterns` | empty | Regexes. Lines matching any pattern are ignored (test fixtures, documented examples). |
| `extra_patterns` | empty | Map of `name: regex`. Each match reports rule `security.<name>`. |

Built-in rules: `security.aws-key`, `security.github-token`, `security.slack-token`, `security.private-key`, `security.stripe-live`, `security.npm-token`, `security.google-api-key`, `security.openai-key`, `security.anthropic-key`, `security.sendgrid-key`, `security.assigned-secret`, `security.high-entropy-secret`.

### `design` (hard)

Project rules from `rules_path`.

| Key | Default | Meaning |
|---|---|---|
| `rules_path` | `.aiv/design-rules.yaml` | Rule file. |
| `file_extensions` / `languages` | built-in list | Files to scan. |

Rule file:

```yaml
constraints:
  - id: no-system-exit
    keywords: []                      # when non-empty, the rule applies only to files containing one
    forbidden_calls: [System.exit]    # any match fails
    required_calls: []                # all must be present when the rule applies
```

Rules: `design.forbidden.<constraint-id>`, `design.required.<constraint-id>`.

### `duplication` (advisory)

| Key | Default | Meaning |
|---|---|---|
| `min_lines` | `6` | Consecutive significant added lines that must match existing code. |
| `max_files` | `5000` | Workspace files scanned. |
| `max_file_bytes` | `524288` | Larger files are skipped. |
| `include_tests` | `false` | Also probe changed test files. |
| `file_extensions` / `languages` | built-in list | Files to scan. |

Rules: `duplication.clone`.

### `density` (advisory recommended)

| Key | Default | Meaning |
|---|---|---|
| `ldr_threshold` | `0.25` | Minimum logic density ratio. Java only. |
| `entropy_threshold` | `4.0` | Minimum character entropy. |
| `entropy_min_bytes` | `300` | Files smaller than this skip the entropy check. |
| `refactor_net_loc_threshold` | `-50` | Files whose net line change is at or below this value are exempt. |
| `trusted_authors` | empty | Author emails exempt when the head commit has a good signature. |
| `file_extensions` / `languages` | built-in list | Files to scan. |

Rules: `density.ldr`, `density.entropy`.

### `cohesion` (advisory recommended)

| Key | Default | Meaning |
|---|---|---|
| `max_areas` | `3` | Maximum distinct directory areas touched. |
| `area_depth` | `2` | Path depth that defines an area. |
| `max_files` | `0` | Maximum changed files; `0` disables. |

Rules: `cohesion.areas`, `cohesion.files`.

### `doc-integrity` (opt-in)

| Key | Default | Meaning |
|---|---|---|
| `rules_path` | `.aiv/doc-rules.yaml` | Rule file with `doc_constraints` and `canonical_commands`. |
| `auto` | `false` | Run only when the diff touches documentation. `--include-doc-checks` forces the gate on. |

### `pr-metadata` (opt-in)

Reads the PR title and body from `AIV_PR_TITLE` and `AIV_PR_BODY`. The composite action exports both.

| Key | Default | Meaning |
|---|---|---|
| `min_title_words` | `2` | Titles with fewer words fail. |
| `require_disclosure` | `false` | Require a Gen-AI disclosure marker in the body. |

### `license-header` (opt-in)

Added source files must carry a license header.

| Key | Default | Meaning |
|---|---|---|
| `header_markers` | Apache 2.0 text or SPDX `Apache-2.0` | Case-insensitive strings; any one in the first 4 KB satisfies the check. Replaces the default list. |
| `generated_markers` | `generated by`, `do not edit`, `@generated` | Files whose head contains one are skipped. |
| `file_extensions` / `languages` | built-in source list | Files that must carry a header. |
| `exclude_paths` | empty | Globs never checked. |

Rules: `license-header.missing`.

### `external` (opt-in)

Runs analyzers the project already trusts and folds their SARIF results into the AIV verdict. AIV does not re-implement them; it runs the command, reads SARIF, and reports each result under the same trust boundary as the built-in gates (base-ref config, no silent skips). The gate only runs when `enabled: true` and at least one `tools:` entry exists.

```yaml
- id: external
  enabled: true
  severity: warn
  config:
    tools:
      - id: ruff-airflow
        command: ["ruff", "check", "--select", "AIR", "--output-format", "sarif", "--exit-zero", "{files}"]
        sarif: stdout
        severity: warn
        min_level: warning
        changed_files_only: true
        file_extensions: [.py]
        timeout_seconds: 120
        on_missing: skip
```

Per-tool keys:

| Key | Default | Meaning |
|---|---|---|
| `id` | required | Short name; becomes part of every rule id. |
| `command` | required | Argument list. `{files}` expands to the changed files that match `file_extensions`. If it is present and no file matches, the tool is not run. |
| `sarif` | `stdout` | Where the SARIF is: `stdout` or a path (relative to the workspace) that the tool writes. |
| `severity` | `fail` | `warn` makes the tool advisory; anything else blocks. |
| `min_level` | `warning` | Drop SARIF results below `none`, `note`, `warning` or `error`. |
| `changed_files_only` | `true` | Drop results whose file is not in the diff. |
| `file_extensions` / `languages` | all changed files | Which changed files are passed to `{files}`. |
| `timeout_seconds` | `120` | Kill the tool after this long. |
| `on_missing` | `skip` | `skip` records the tool as `SKIPPED` in the summary when the binary is missing or times out; `fail` turns that into a blocking finding. |

Rules: `external.<tool-id>.<sarif-rule-id>` for each result; `external.<tool-id>.missing`, `.timeout` (only with `on_missing: fail`); `external.<tool-id>.no-output` and `.bad-output` always block, because a tool that ran but produced no SARIF is not evidence.

### `rules` (opt-in, generic rule engine)

AIV ships no framework-specific gate. Framework knowledge lives in rule packs (YAML shipped inside the jar under `rulepacks/<name>.yaml`) and in your own rule documents. The engine is the same for every pack and every language: it works on added lines of the files a rule selects, and it understands Python indentation bodies as well as brace bodies (Java, Kotlin, Go, JavaScript, TypeScript, C#).

```yaml
- id: rules
  enabled: true
  severity: warn
  config:
    packs: [airflow]                    # shipped packs
    rules_files: [".aiv/rules/*.yaml"]  # your rule documents (globs, relative to the workspace, read from the base ref)
    rules: []                           # inline rules, same schema as a document's rules list
    params:                             # override any pack or document param
      known_conn_ids: [aws_default, google_cloud_default]
    disabled_rules: [airflow.missing-catchup]
```

| Gate key | Default | Meaning |
|---|---|---|
| `packs` | empty | Shipped packs to load. Unknown names fail the run with exit 2. |
| `rules_files` | empty | Globs or paths of rule documents. A plain path that does not exist is skipped; an unreadable file fails the run. |
| `rules` | empty | Inline rules. |
| `params` | empty | Values for `${name}` references in packs and documents. A list param replaces the pack default. |
| `disabled_rules` | empty | Rule ids to drop (`airflow.missing-catchup` or `rules.airflow.missing-catchup`). |

A rule document:

```yaml
name: team            # optional; pack rules get ids rules.<name>.<id>, documents without a name get rules.<id>
params:               # defaults for ${...} references
  banned_hosts: [internal.example.com]
defaults:             # merged into every rule unless the rule sets the key
  paths: ["**/*.py"]
  file_filter: "^\\s*(from|import)\\s+airflow"   # regex the whole file must match before any rule runs
rules:
  - id: no-print
    kind: pattern
    pattern: "\\bprint\\("
    severity: warn
    message: "print() in production code"
```

Rule keys (all kinds): `id`, `kind` (`pattern`, `call`, `hollow-function`; default `pattern`), `paths` (globs; `**` crosses directories), `file_filter` (regex on the file content), `added_lines_only` (default `true`; `false` scans the whole file), `message` (may use `{value}`), `severity` (`warn` makes that rule advisory even when the gate blocks).

| Kind | Keys | Fires when |
|---|---|---|
| `pattern` | `pattern`; optional `capture_group`, `capture_matches`, `allowed` or `allowed_param`, `skip_when_allowed_empty` (default `true`) | The regex matches a line. With a capture, the captured value must also match `capture_matches`, or must be absent from the allowlist. |
| `call` | `call` (callee regex; the engine appends `\s*\(` and reads the whole balanced call, across lines); `if_arg`, `require_arg`, or `capture` + `capture_matches` / `allowed` | `require_arg` is not passed as a keyword (optionally only when `if_arg` is), or the captured value matches / is not allowed. |
| `hollow-function` | `function` (name regex) | The function body is only `pass`, `return`, `...`, `raise NotImplementedError`, `throw new UnsupportedOperationException(...)`, `panic(...)`, or comments. |

`${name}` in any string is replaced by the param. A list param becomes a quoted alternation `(?:a|b)`; `${name|raw}` joins the items as regex fragments; an empty list becomes `(?!)`, which matches nothing.

Shipped pack `airflow` (rule ids `rules.airflow.*`): `start-date-now`, `missing-catchup`, `example-dag-id`, `sensor-no-timeout`, `hollow-execute`, `placeholder-task`, `removed-import`, `unknown-conn-id`. Params: `example_dag_id_pattern` (`^(example|test|my|sample|demo)_`), `known_conn_ids` (empty; the rule is silent until filled), `placeholder_commands` (regex list), `removed_imports` (Airflow 3 removed paths). The pack source is `aiv-plugin-rules/src/main/resources/rulepacks/airflow.yaml`; copy it into `.aiv/rules/` to fork it.

### `jev` (opt-in, advisory only, model-assisted)

The one gate that calls a model, and the only gate that can never block: whatever severity is configured, its outcome is pass or advisory. It sends the diff, the changed file list and the PR title/body to a System One decision model and asks the yes/no questions you configure. Each answer is a calibrated probability that is written into the receipt as `jev.<check-id>` when it reaches `report_at`. Hosts: TypeSafe (`https://api.typesafe.ai`, `TYPESAFE_API_KEY`), any gateway speaking the same `POST /v1/systemone` API, or a local Ollama server with a decision model (`api_key_env: ""`). Cost is the host's; TypeSafe lists $0.042 per million input tokens with output free (September 2026). Nothing runs when the key is missing; the receipt then says `SKIPPED jev: ... not set`.

```yaml
- id: jev
  enabled: true
  config:
    base_url: https://api.typesafe.ai
    api_key_env: TYPESAFE_API_KEY
    model: jev-latest
    report_at: 0.75
    max_diff_chars: 60000
    timeout_seconds: 30
    checks:
      description-mismatch: "Does `diff` change behaviour that `pr_title` and `pr_body` do not mention?"
      tests-only-call:
        question: "Do the tests added in `diff` call the new code without checking its result?"
        yes: "At least one added test has no meaningful expectation about the outcome"
        no: "Every added test checks a result, an exception or a side effect"
```

| Key | Default | Meaning |
|---|---|---|
| `base_url` | `https://api.typesafe.ai` | Host; `/v1/systemone` is appended. |
| `api_key_env` | `TYPESAFE_API_KEY` | Environment variable holding the bearer token. Empty string: send no token (local Ollama). |
| `model` | `jev-latest` | Model id in the host's format. |
| `report_at` | `0.75` | P(yes) at or above this becomes a finding. Everything below is still printed in the gate summary. |
| `max_diff_chars` | `60000` | The diff is cut here before sending; the cut is marked in the text. |
| `timeout_seconds` | `30` | Per request. |
| `checks` | required | Map of check id to question (string) or `{question, yes, no}`. At most 64. |

Rules: `jev.<check-id>`. Failure modes (missing key, HTTP error, non-JSON response, timeout) are reported as `SKIPPED jev: <reason>` and never hidden.

---

## 5. Inline suppressions

| Directive | Effect |
|---|---|
| `// aiv-disable-next-line [rule]` | Suppress findings on the next line. |
| `code(); // aiv-disable-line [rule]` | Suppress findings on the same line. |
| `// aiv-disable <rule>` | File-level suppression. **The rule id is mandatory**; a bare directive or `*` suppresses nothing. |

Rule values accept an exact id (`invariant.placeholder`) or a family (`security.*`). `#`, `--` and `/* */` comment styles work as well.

---

## 6. CLI

```text
java -jar aiv-cli.jar [doctor] [options]
java -jar aiv-cli.jar init --workspace . [--preset minimal|java-ci|agent-paste-strict|asf|airflow]
java -jar aiv-cli.jar explain <gate-or-rule-id>
java -jar aiv-cli.jar audit verify <log> [--expect-head <sha256>]
java -jar aiv-cli.jar report --audit-log <log> [--out report.html] [--labels labels.csv] [--export-jsonl dir] [--expect-head <sha256>] [--min-labeled N]
java -jar aiv-cli.jar proposal check|scan ...      (see EVIDENCE-AND-PROPOSALS.md)
java -jar aiv-cli.jar --version
```

| Option | Meaning |
|---|---|
| `--workspace <dir>` | Repository root. Default: current directory. |
| `--diff <base-ref>` | Base ref. Default `origin/main`. |
| `--head <ref>` | Head ref. Default `HEAD`. |
| `--diff-json <file>` | Evaluate an in-memory diff instead of Git. |
| `--config-from-workspace` | Read config from the working tree instead of the base ref. |
| `--baseline <file>` | Baseline suppressions. |
| `--include-doc-checks` | Force the doc-integrity gate on. |
| `--fix` | Rewrite conflict markers and elision lines before evaluating. |
| `--output-json <file>` | Write the JSON report (`schema_version: 2`). |
| `--output-sarif <file>` | Write SARIF 2.1.0. |
| `--publish-github-checks` | Post a GitHub Check with annotations. |
| `--publish-pr-comments` | Post one review comment per finding with a fixed hint. |
| `--label-ai-slop [name]` | Apply or remove the deterministic findings label. Default name `AI-SLOP`. |
| `--comment-ai-slop` | Create, update or delete the deterministic findings comment. |
| `--label-pr-on-advisory [name]` | Apply or remove the advisory label. |
| `--warnings-exit-code <n>` | Exit with `n` when the run passed with notices. |
| `--quiet` | Suppress the human-readable report. |
| `--audit-log <file>` | Append this run to a hash-chained audit log and print the `[AUDIT]` anchor line (also added to the JSON report notices and the PR comment). |
| `doctor` / `--doctor` | Informational run, always exits 0. |

Exit codes: `0` pass, `1` a blocking gate failed, `2` invalid arguments or configuration (including an audit log that cannot be written), `3` Git failure. `audit verify` exits `0` intact, `1` tampered or anchor mismatch, `2` usage. `report` exits `0` when the chain verifies and `1` when it does not (the report is still written and says where the chain breaks).

Every run also reports, as notices, what a reviewer would otherwise not see: `[CONFIG-CHANGE]` (the change edits `.aiv/`), `[SKIP-IGNORED]` and `[SKIP-HONORED]` (`/aiv skip` attempts, with the author when honoured) and `[SUPPRESSED]` (findings removed by a baseline or inline `aiv-disable`, with the rule ids). With an audit log these become the `overrides` field of the record.

---

## 7. Environment variables

| Variable | Used by |
|---|---|
| `GITHUB_TOKEN`, `GITHUB_REPOSITORY` | Checks, labels, comments. The composite action exports the token. |
| `AIV_GITHUB_PR_NUMBER` or `GITHUB_EVENT_PATH` | PR number resolution. |
| `GITHUB_SHA` or `AIV_GITHUB_HEAD_SHA` | Review comments and checks. |
| `AIV_PR_TITLE`, `AIV_PR_BODY`, `AIV_PR_TEMPLATE` | `pr-metadata` gate. |
| `AIV_LABEL_AI_SLOP=true`, `AIV_AI_SLOP_LABEL` | Enable and name the deterministic label without CLI flags. |
| `AIV_COMMENT_AI_SLOP=true` | Enable the deterministic comment without CLI flags. |
| `AIV_LABEL_PR_ON_ADVISORY=true`, `AIV_PR_ADVISORY_LABEL` | Advisory label. |
| `AIV_AUDIT_LOG` | Audit log path. Wins over the `audit_log` key; `--audit-log` wins over both. |
| `TYPESAFE_API_KEY` (or the name in `jev.api_key_env`) | `jev` gate only. |
| `AIV_GITHUB_API_BASE`, `AIV_GITHUB_PR_COMMENTS_API_BASE`, `AIV_GITHUB_FINDINGS_API_BASE` | API base overrides for GitHub Enterprise or proxies. |

GitHub permissions: labels and comments need `pull-requests: write`; checks need `checks: write`. On pull requests from forks the default token is read-only, so publication fails with a clear error and the gate result is unaffected.

---

## 8. Complete example

```yaml
schema_version: 1
fail_fast: false
exclude_paths: ["**/generated/**", "docs/**"]

ai_slop_pr_label: AI-SLOP
ai_slop_pr_comment: true
ai_slop_label_gates: [invariant, security, package-existence, test-adequacy]

gates:
  - id: syntax
    enabled: true
  - id: invariant
    enabled: true
    config:
      placeholder_words: [TBD, FIXME, XXX, HACK]
      extra_artifact_patterns: ["TODO: implement actual logic"]
  - id: test-adequacy
    enabled: true
  - id: package-existence
    enabled: true
    config:
      allow: [internal-sdk]
  - id: security
    enabled: true
    config:
      allow_patterns: ["tests/fixtures/"]
      extra_patterns:
        internal-token: "\\bINT-[A-Z0-9]{24}\\b"
  - id: dependency
    enabled: true
  - id: design
    enabled: true
    config:
      rules_path: .aiv/design-rules.yaml
  - id: duplication
    enabled: true
    severity: warn
  - id: density
    enabled: true
    severity: warn
  - id: cohesion
    enabled: true
    severity: warn
    config:
      max_areas: 4
  - id: pr-metadata
    enabled: true
    severity: warn
    config:
      require_disclosure: true
  - id: license-header
    enabled: false
  - id: doc-integrity
    enabled: false
```
