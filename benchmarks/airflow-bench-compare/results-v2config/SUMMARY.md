# v2 airflow preset on aiv-airflow-bench

Measurement run: the shipped `airflow` preset (all gates advisory) written into the working tree and read with
`--config-from-workspace`. The preset is advisory, so a non-zero exit is a defect in AIV, not a verdict;
the question is what evidence each gate produces.

| Cohort | n | non-zero exits | any signal | excl. syntax | gates fired (branches) | rules fired (findings) |
|---|---|---|---|---|---|---|
| abandoned/abandoned_clean | 8 | 0 | 4 | 4 | external 4, test-adequacy 3 | external.ruff-airflow.invalid-syntax 97, test-adequacy.assertion-free-test 2, test-adequacy.missing-tests 1 |
| abandoned/ai_disclosed | 16 | 0 | 15 | 15 | external 12, test-adequacy 7 | external.ruff-airflow.invalid-syntax 216, test-adequacy.missing-tests 6, test-adequacy.assertion-free-test 5, external.ruff-airflow.no-output 1 |
| abandoned/ai_signal_advisory | 3 | 0 | 1 | 1 | external 1, test-adequacy 1 | test-adequacy.missing-tests 4, external.ruff-airflow.no-output 1 |
| case | 4 | 0 | 4 | 4 | test-adequacy 4, invariant 2, external 1 | external.ruff-airflow.invalid-syntax 13, test-adequacy.missing-tests 4, invariant.ai-edit-artifact 1, invariant.merge-conflict 1 |
| labeled | 50 | 0 | 50 | 50 | test-adequacy 45, invariant 31, external 7, cohesion 5 | external.ruff-airflow.invalid-syntax 83, test-adequacy.missing-tests 45, invariant.ai-edit-artifact 21, invariant.merge-conflict 6, cohesion.areas 5, invariant.placeholder 3, invariant.ai-provenance 1 |
| origin | 1 | 0 | 1 | 1 | test-adequacy 1, invariant 1 | invariant.ai-edit-artifact 1, test-adequacy.missing-tests 1 |
| snapshot | 7 | 0 | 4 | 4 | external 4, test-adequacy 1 | external.ruff-airflow.invalid-syntax 54, test-adequacy.assertion-free-test 2, test-adequacy.missing-tests 1 |
