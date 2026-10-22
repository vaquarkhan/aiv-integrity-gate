# `aiv-plugin-rules`

This module contributes the generic `rules` quality gate. Its Java code has no
framework-specific behavior. Domain knowledge lives in classpath YAML packs,
project-owned YAML rule files, or inline configuration. A framework such as
Apache Airflow is one example of this mechanism, not the module's primary
purpose.

## Architecture role

`RulesGate` loads rule sources, scopes matching to changed content, and combines
blocking and advisory findings. `RuleLoader` validates YAML and compiles
parameters and regular expressions. `RuleEngine` performs pattern, call, and
hollow-function matching. `Globs` handles path selection, and `Rule` is the
compiled rule representation.

This module is the extension point for deterministic checks that need more than
the literal required and forbidden matching in `aiv-plugin-design` but do not
justify a new Java plugin.

## Execution flow

1. Read `packs`, `rules_files`, inline `rules`, parameter overrides, and disabled
   IDs.
2. Load named packs from `rulepacks/<name>.yaml` in the plugin JAR.
3. Resolve project rule paths and globs against
   `AIVContext.getPolicyWorkspace()`.
4. Parse and compile all rules. Missing configured files, unknown packs, invalid
   YAML, invalid schema, and invalid regular expressions fail closed.
5. Parse added lines from the unified diff. New files and evaluations without a
   diff index use the complete changed-file content.
6. Apply path globs, whole-file filters, added-line scoping, and rule logic.
7. Return fail when any blocking rule fires, advisory when only warning rules
   fire, or pass otherwise.

## Rule kinds

| Kind | Behavior |
|---|---|
| `pattern` | Applies a regular expression and can validate a captured value against another expression or allowlist. |
| `call` | Reads a balanced call across at most 200 lines and checks arguments or captured values. |
| `hollow-function` | Finds Python indentation bodies or brace bodies containing only recognized placeholder statements. |

Common rule keys include `id`, `kind`, `paths`, `file_filter`,
`added_lines_only`, `message`, and `severity`. `added_lines_only` defaults to
`true`. Only `severity: warn` is advisory; every other value is blocking.

Pattern rules support `pattern`, `capture_group`, `capture_matches`, `allowed`,
`allowed_param`, and `skip_when_allowed_empty`. Call rules additionally use
`call`, `if_arg`, `require_arg`, and `capture`. Hollow-function rules identify a
function using their function expression.

## Rule IDs

Rules loaded from a named pack use `rules.<pack-name>.<rule-id>`. Project files
and inline rules use `rules.<rule-id>`.

The included Airflow example pack provides:

- `rules.airflow.start-date-now`
- `rules.airflow.missing-catchup`
- `rules.airflow.example-dag-id`
- `rules.airflow.sensor-no-timeout`
- `rules.airflow.hollow-execute`
- `rules.airflow.placeholder-task`
- `rules.airflow.removed-import`
- `rules.airflow.unknown-conn-id`

## Configuration and defaults

| Key | Default | Meaning |
|---|---|---|
| `packs` | `[]` | Named classpath rule packs. |
| `rules_files` | `[]` | Policy-workspace YAML paths or forward-slash globs. |
| `rules` | `[]` | Inline rules using the same schema. |
| `params` | `{}` | Overrides for pack or document parameters. |
| `disabled_rules` | `[]` | Case-insensitive IDs, with optional leading `rules.`. |

A rule document can define `name`, `params`, `defaults`, and `rules`.
Configuration parameter overrides take precedence over document defaults.
`${name}` expands list parameters as quoted regular-expression alternatives.
`${name|raw}` joins list entries as raw expressions. An empty list expands to a
never-match expression. Allowlist rules skip by default when their allowed list
is empty.

## Generic example

```yaml
- id: rules
  enabled: true
  severity: block
  config:
    rules_files: [".aiv/rules/*.yaml"]
    params:
      approved_clients: [SafeClient]
    disabled_rules: []
```

```yaml
# .aiv/rules/project.yaml
name: project
params:
  approved_clients: []
rules:
  - id: disallowed-client
    kind: pattern
    paths: ["src/**/*.java"]
    pattern: "new\\s+([A-Za-z0-9_]+Client)\\s*\\("
    capture_group: 1
    allowed_param: approved_clients
    message: "Client {value} is not approved"
```

To opt into the included Airflow example, add `packs: [airflow]`. Its parameters
are `example_dag_id_pattern`, `known_conn_ids`, `placeholder_commands`, and
`removed_imports`.

## Hard and advisory behavior

A rule with `severity: warn` contributes an advisory finding. Other rule
severities contribute blocking findings. If both occur, the result fails and
contains both sets of findings. Configuration and policy loading errors are
thrown rather than converted into advisory findings.

The gate-level host severity may further classify the aggregate result, but
rule-level warning behavior is implemented inside this plugin.

## Immutable policy workspace

Project `rules_files` are loaded from the policy workspace, not from the source
workspace under review. In a trusted run, the host can supply an immutable
snapshot of the selected policy revision. This prevents a proposed change from
editing or deleting the rules used for its own verdict and keeps rule loading
consistent throughout evaluation. Shipped packs are immutable JAR resources.
Inline rules and pack selection come from the configuration supplied by the
host. The plugin does not fetch a Git reference or independently prove that the
provided policy workspace and configuration are immutable.

## Inputs and outputs

Inputs are changed-file paths and contents, raw unified diff, compiled gate
configuration, classpath packs, and policy-side rule files. Output is a pass,
advisory, or fail `GateResult` with stable IDs and 1-based file locations.

## Interactions and extension points

Create YAML documents for organization or project conventions and add classpath
packs for reusable domains. Use parameters for repository-specific allowlists
instead of embedding them in Java. Use `aiv-plugin-design` for simpler literal
constraints and `aiv-plugin-external` when a mature analyzer already emits
SARIF. New rule kinds require changes to `RuleLoader`, `Rule`, and `RuleEngine`
plus tests.

## Honest boundaries

The engine analyzes text and lightweight structure, not a language AST or
symbol table. Added-line membership is based on trimmed text and can conflate
identical lines. Balanced call scanning does not fully model lexical quoting.
Generated-at-runtime code is invisible. Hollow-function recognition supports a
bounded set of placeholder forms. A project rule glob that matches no files is
a hard configuration error.

The Airflow pack is fixture-tested, but that does not establish field precision
for every Airflow repository. In particular, `unknown-conn-id` skips when
`known_conn_ids` is empty unless configured otherwise.

## Tests and build

`RulesGateTest` covers generic project and inline rules, warning and blocking
aggregation, policy-workspace precedence, fail-closed loading, schema and
parameter behavior, globs, calls, hollow functions, and all included Airflow
example rules.

From this module:

```shell
mvn test
```

From the parent reactor:

```shell
mvn -pl aiv-plugin-rules test
```

See also [developer configuration](../docs/DEVELOPER-CONFIGURATION.md) and
`aiv explain rules`.

**Author:** Viquar Khan
