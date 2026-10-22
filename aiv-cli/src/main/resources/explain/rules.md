# Gate: `rules`
## Why it exists
A generic, configuration-driven rule engine. AIV ships no framework-specific gate; framework knowledge lives in rule packs (`packs: [airflow]`) and in your own `.aiv/rules/*.yaml`. Three rule kinds cover most "it imports fine and fails at runtime" defects: `pattern` (regex on added lines, optional capture checked against a regex or an allowlist), `call` (a call that passes one argument but not another, or whose argument value matches a placeholder pattern), and `hollow-function` (a function body that is only `pass`, `return`, `...`, `raise NotImplementedError`, `throw new UnsupportedOperationException`, or comments). Python and brace languages are both understood.
## When a finding is wrong
- Rule ids are `rules.<pack>.<rule>` or `rules.<rule>`. Switch one off with `disabled_rules: [airflow.missing-catchup]` in the gate config, or inline with `# aiv-disable-next-line rules.airflow.missing-catchup`.
- Pack params are overridable: `params: { example_dag_id_pattern: "^demo_", known_conn_ids: [aws_default] }`. A list param replaces the pack default; repeat the defaults you want to keep.
- A rule with an `allowed_param` whose list is empty is silent until you fill the list.
## How to fix
- Fix the code the message points at; the message is written by the rule author, so a pack explains the defect in its own words.
- Add project rules under `.aiv/rules/*.yaml` on the protected branch. The schema is in docs/DEVELOPER-CONFIGURATION.md, section "rules".
- Shipped packs: `airflow` (start-date-now, missing-catchup, example-dag-id, sensor-no-timeout, hollow-execute, placeholder-task, removed-import, unknown-conn-id). The `airflow` preset enables it advisory.
