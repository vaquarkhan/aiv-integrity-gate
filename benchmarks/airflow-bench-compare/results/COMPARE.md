# Old (1.0.4, commit bd9a99d) vs new (v2 working tree) on aiv-airflow-bench

89 branches, base ref `main` (three-dot merge-base diff), identical `.aiv/config.yaml` for both builds (the new build reads it from the base ref).

## 1. Labeled ground truth (synthetic marker fixtures + case/*)

| build | n | TP | FN | FP | TN | recall | precision |
|---|---|---|---|---|---|---|---|
| old | 54 | 38 | 0 | 0 | 16 | 100% | 100% |
| new | 54 | 38 | 0 | 0 | 16 | 100% | 100% |

Both builds score the same here. These fixtures were generated to match the invariant/design regexes; they prove the regexes work, not that slop is detected.

## 2. Real abandoned Airflow PRs (recreated as hunk fragments; no hard ground truth)

| cohort | n | old hard | new hard | old hard excl. syntax | new hard excl. syntax | old any signal excl. syntax | new any signal excl. syntax |
|---|---|---|---|---|---|---|---|
| ai_disclosed | 16 | 13 | 14 | 0 | 0 | 0 | 3 |
| ai_signal_advisory | 3 | 2 | 2 | 0 | 0 | 1 | 2 |
| abandoned_clean | 8 | 5 | 5 | 0 | 0 | 0 | 1 |
| all | 27 | 20 | 21 | 0 | 0 | 1 | 6 |
| snapshot | 7 | 5 | 5 | 0 | 0 | 0 | 0 |

Corpus caveat: abandoned/* and snapshot/* branches contain hunk fragments, not whole files; syntax verdicts on them are a corpus artifact shared by both builds.
Assertion-free findings whose test body is cut off at the fragment's EOF are excluded from the new build's 'excl. syntax' columns as corpus artifacts (9 findings): abandoned/abandoned_clean/airflow-pr-73106 providers/abandoned/pr-73106/providers/cncf/kubernetes/tests/unit/cncf/kubernetes/utils/test_pod_manager.py:20; abandoned/abandoned_clean/airflow-pr-73155 providers/abandoned/pr-73155/shared/configuration/tests/configuration/test_parser.py:47; abandoned/ai_disclosed/airflow-pr-72844 providers/abandoned/pr-72844/airflow-core/src/airflow/ui/src/layouts/Nav/Nav.test.tsx:55; abandoned/ai_disclosed/airflow-pr-73096 providers/abandoned/pr-73096/tests/www/views/test_views_home.py:24; abandoned/ai_disclosed/airflow-pr-73105 providers/abandoned/pr-73105/airflow-core/tests/unit/cli/commands/test_dag_command.py:10; abandoned/ai_disclosed/airflow-pr-73271 providers/abandoned/pr-73271/providers/smtp/tests/unit/smtp/notifications/test_smtp.py:48; abandoned/ai_disclosed/airflow-pr-73271 providers/abandoned/pr-73271/task-sdk/tests/task_sdk/execution_time/test_task_runner.py:14; snapshot/airflow-pr-73271 providers/snapshot/pr-73271/providers/smtp/tests/unit/smtp/notifications/test_smtp.py:48; snapshot/airflow-pr-73271 providers/snapshot/pr-73271/task-sdk/tests/task_sdk/execution_time/test_task_runner.py:14

### Gates that fired on real PRs (new build, excl. syntax)

- all abandoned: gates {'syntax': 20, 'test-adequacy': 11, 'density': 1} ; rules {'syntax.parse': 20, 'test-adequacy.assertion-free-test': 6, 'test-adequacy.missing-tests': 8, 'density.entropy': 1}

## 3. Per branch

| branch | expected | old | new | old hard | new hard | new soft | new rules | notices |
|---|---|---|---|---|---|---|---|---|
| abandoned/abandoned_clean/airflow-pr-73059 | n/a | FAIL | FAIL | syntax | syntax | - | syntax.parse | - |
| abandoned/abandoned_clean/airflow-pr-73072 | n/a | PASS | PASS | - | - | - | - | - |
| abandoned/abandoned_clean/airflow-pr-73106 | n/a | FAIL | FAIL | syntax | syntax;test-adequacy | - | syntax.parse;test-adequacy.assertion-free-test | - |
| abandoned/abandoned_clean/airflow-pr-73137 | n/a | FAIL | FAIL | syntax | syntax | - | syntax.parse | - |
| abandoned/abandoned_clean/airflow-pr-73155 | n/a | FAIL | FAIL | syntax | syntax;test-adequacy | - | syntax.parse;test-adequacy.assertion-free-test | - |
| abandoned/abandoned_clean/airflow-pr-73186 | n/a | PASS | PASS | - | - | - | - | - |
| abandoned/abandoned_clean/airflow-pr-73201 | n/a | FAIL | FAIL | syntax | syntax | test-adequacy | syntax.parse;test-adequacy.missing-tests | - |
| abandoned/abandoned_clean/airflow-pr-73278 | n/a | PASS | PASS | - | - | - | - | - |
| abandoned/ai_disclosed/airflow-pr-70250 | n/a | FAIL | FAIL | syntax | syntax | - | syntax.parse | - |
| abandoned/ai_disclosed/airflow-pr-72844 | n/a | PASS | FAIL | - | test-adequacy | - | test-adequacy.assertion-free-test | - |
| abandoned/ai_disclosed/airflow-pr-72870 | n/a | FAIL | FAIL | syntax | syntax | - | syntax.parse | - |
| abandoned/ai_disclosed/airflow-pr-72986 | n/a | FAIL | FAIL | syntax | syntax | - | syntax.parse | - |
| abandoned/ai_disclosed/airflow-pr-73056 | n/a | FAIL | FAIL | syntax | syntax | test-adequacy | syntax.parse;test-adequacy.missing-tests | - |
| abandoned/ai_disclosed/airflow-pr-73068 | n/a | FAIL | FAIL | syntax | syntax | - | syntax.parse | - |
| abandoned/ai_disclosed/airflow-pr-73069 | n/a | FAIL | FAIL | syntax | syntax | - | syntax.parse | - |
| abandoned/ai_disclosed/airflow-pr-73075 | n/a | FAIL | FAIL | syntax | syntax | - | syntax.parse | - |
| abandoned/ai_disclosed/airflow-pr-73096 | n/a | FAIL | FAIL | syntax | syntax;test-adequacy | - | syntax.parse;test-adequacy.assertion-free-test;test-adequacy.missing-tests | - |
| abandoned/ai_disclosed/airflow-pr-73105 | n/a | FAIL | FAIL | syntax | syntax;test-adequacy | - | syntax.parse;test-adequacy.assertion-free-test;test-adequacy.missing-tests | - |
| abandoned/ai_disclosed/airflow-pr-73124 | n/a | FAIL | FAIL | syntax | syntax | - | syntax.parse | - |
| abandoned/ai_disclosed/airflow-pr-73176 | n/a | PASS | PASS | - | - | test-adequacy | test-adequacy.missing-tests | - |
| abandoned/ai_disclosed/airflow-pr-73192 | n/a | PASS | PASS | - | - | test-adequacy | test-adequacy.missing-tests | - |
| abandoned/ai_disclosed/airflow-pr-73271 | n/a | FAIL | FAIL | syntax | syntax;test-adequacy | - | syntax.parse;test-adequacy.assertion-free-test;test-adequacy.missing-tests | - |
| abandoned/ai_disclosed/airflow-pr-73289 | n/a | FAIL | FAIL | syntax | syntax | - | syntax.parse | - |
| abandoned/ai_disclosed/airflow-pr-73326 | n/a | FAIL | FAIL | syntax | syntax | - | syntax.parse | - |
| abandoned/ai_signal_advisory/airflow-pr-72977 | n/a | FAIL | FAIL | syntax | syntax | test-adequacy | syntax.parse;test-adequacy.missing-tests | - |
| abandoned/ai_signal_advisory/airflow-pr-72983 | n/a | FAIL | FAIL | syntax | syntax | - | syntax.parse | - |
| abandoned/ai_signal_advisory/airflow-pr-73195 | n/a | PASS | PASS | - | - | density | density.entropy | - |
| case/ai-attribution | FAIL | FAIL | FAIL | design | design | test-adequacy | design.forbidden.no-ai-generated-markers;test-adequacy.missing-tests | - |
| case/ai-edit-artifact | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| case/clean-control | PASS | PASS | PASS | - | - | test-adequacy | test-adequacy.missing-tests | - |
| case/merge-conflict | FAIL | FAIL | FAIL | invariant;syntax | invariant;syntax | test-adequacy | invariant.merge-conflict;syntax.parse;test-adequacy.missing-tests | - |
| labeled/mixed-cohesion-01 | PASS | PASS | PASS | - | - | cohesion | cohesion.areas | - |
| labeled/mixed-cohesion-02 | PASS | PASS | PASS | - | - | cohesion | cohesion.areas | - |
| labeled/mixed-cohesion-03 | PASS | PASS | PASS | - | - | cohesion | cohesion.areas | - |
| labeled/mixed-cohesion-04 | PASS | PASS | PASS | - | - | cohesion | cohesion.areas | - |
| labeled/mixed-cohesion-05 | PASS | PASS | PASS | - | - | cohesion | cohesion.areas | - |
| labeled/mixed-hard-01 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/mixed-hard-02 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/mixed-hard-03 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/mixed-hard-04 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/mixed-hard-05 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/mixed-soft-01 | PASS | PASS | PASS | - | - | test-adequacy | test-adequacy.missing-tests | - |
| labeled/mixed-soft-02 | PASS | PASS | PASS | - | - | test-adequacy | test-adequacy.missing-tests | - |
| labeled/mixed-soft-03 | PASS | PASS | PASS | - | - | test-adequacy | test-adequacy.missing-tests | - |
| labeled/mixed-soft-04 | PASS | PASS | PASS | - | - | test-adequacy | test-adequacy.missing-tests | - |
| labeled/mixed-soft-05 | PASS | PASS | PASS | - | - | test-adequacy | test-adequacy.missing-tests | - |
| labeled/proper-01 | PASS | PASS | PASS | - | - | test-adequacy | test-adequacy.missing-tests | - |
| labeled/proper-02 | PASS | PASS | PASS | - | - | test-adequacy | test-adequacy.missing-tests | - |
| labeled/proper-03 | PASS | PASS | PASS | - | - | test-adequacy | test-adequacy.missing-tests | - |
| labeled/proper-04 | PASS | PASS | PASS | - | - | test-adequacy | test-adequacy.missing-tests | - |
| labeled/proper-05 | PASS | PASS | PASS | - | - | test-adequacy | test-adequacy.missing-tests | - |
| labeled/slop-attr-01 | FAIL | FAIL | FAIL | design | design | test-adequacy | design.forbidden.no-ai-generated-markers;test-adequacy.missing-tests | - |
| labeled/slop-attr-02 | FAIL | FAIL | FAIL | design | design | test-adequacy | design.forbidden.no-ai-generated-markers;test-adequacy.missing-tests | - |
| labeled/slop-attr-03 | FAIL | FAIL | FAIL | design;invariant;syntax | design;invariant;syntax | test-adequacy | design.forbidden.no-ai-generated-markers;invariant.ai-provenance;syntax.parse;test-adequacy.missing-tests | - |
| labeled/slop-attr-04 | FAIL | FAIL | FAIL | design | design | test-adequacy | design.forbidden.no-ai-generated-markers;test-adequacy.missing-tests | - |
| labeled/slop-attr-05 | FAIL | FAIL | FAIL | design | design | test-adequacy | design.forbidden.no-ai-generated-markers;test-adequacy.missing-tests | - |
| labeled/slop-attr-06 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/slop-conflict-01 | FAIL | FAIL | FAIL | invariant;syntax | invariant;syntax | test-adequacy | invariant.merge-conflict;syntax.parse;test-adequacy.missing-tests | - |
| labeled/slop-conflict-02 | FAIL | FAIL | FAIL | invariant;syntax | invariant;syntax | test-adequacy | invariant.merge-conflict;syntax.parse;test-adequacy.missing-tests | - |
| labeled/slop-conflict-03 | FAIL | FAIL | FAIL | invariant;syntax | invariant;syntax | test-adequacy | invariant.merge-conflict;syntax.parse;test-adequacy.missing-tests | - |
| labeled/slop-conflict-04 | FAIL | FAIL | FAIL | invariant;syntax | invariant;syntax | test-adequacy | invariant.merge-conflict;syntax.parse;test-adequacy.missing-tests | - |
| labeled/slop-conflict-05 | FAIL | FAIL | FAIL | invariant;syntax | invariant;syntax | test-adequacy | invariant.merge-conflict;syntax.parse;test-adequacy.missing-tests | - |
| labeled/slop-conflict-06 | FAIL | FAIL | FAIL | invariant;syntax | invariant;syntax | test-adequacy | invariant.merge-conflict;syntax.parse;test-adequacy.missing-tests | - |
| labeled/slop-edit-01 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/slop-edit-02 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/slop-edit-03 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/slop-edit-04 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/slop-edit-05 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/slop-edit-06 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/slop-edit-07 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/slop-edit-08 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/slop-ph-01 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.placeholder;test-adequacy.missing-tests | - |
| labeled/slop-ph-02 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.placeholder;test-adequacy.missing-tests | - |
| labeled/slop-ph-03 | FAIL | FAIL | FAIL | design;invariant | design;invariant | test-adequacy | design.forbidden.no-ai-generated-markers;invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/slop-ph-04 | FAIL | FAIL | FAIL | design;invariant | design;invariant | test-adequacy | design.forbidden.no-ai-generated-markers;invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/slop-ph-05 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.placeholder;test-adequacy.missing-tests | - |
| labeled/slop-search-01 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/slop-search-02 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/slop-search-03 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/slop-search-04 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| labeled/slop-search-05 | FAIL | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| origin | n/a | FAIL | FAIL | invariant | invariant | test-adequacy | invariant.ai-edit-artifact;test-adequacy.missing-tests | - |
| snapshot/airflow-pr-70053 | n/a | PASS | PASS | - | - | - | - | - |
| snapshot/airflow-pr-70250 | n/a | FAIL | FAIL | syntax | syntax | - | syntax.parse | - |
| snapshot/airflow-pr-73124 | n/a | FAIL | FAIL | syntax | syntax | - | syntax.parse | - |
| snapshot/airflow-pr-73271 | n/a | FAIL | FAIL | syntax | syntax;test-adequacy | - | syntax.parse;test-adequacy.assertion-free-test;test-adequacy.missing-tests | - |
| snapshot/airflow-pr-73278 | n/a | PASS | PASS | - | - | - | - | - |
| snapshot/airflow-pr-73289 | n/a | FAIL | FAIL | syntax | syntax | - | syntax.parse | - |
| snapshot/airflow-pr-73326 | n/a | FAIL | FAIL | syntax | syntax | - | syntax.parse | - |
