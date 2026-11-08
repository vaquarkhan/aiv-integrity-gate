# v2 airflow preset on aiv-airflow-bench

Measurement run: the shipped `airflow` preset (all gates advisory) written into the working tree and read with
`--config-from-workspace`. The preset is advisory, so a non-zero exit is a defect in AIV, not a verdict;
the question is what evidence each gate produces.

| Cohort | n | non-zero exits | any signal | excl. syntax | gates fired (branches) | rules fired (findings) |
|---|---|---|---|---|---|---|
| abandoned/abandoned_clean | 8 | 8 | 0 | 0 | none | none |
| abandoned/ai_disclosed | 16 | 16 | 0 | 0 | none | none |
| abandoned/ai_signal_advisory | 3 | 3 | 0 | 0 | none | none |
| case | 4 | 4 | 0 | 0 | none | none |
| labeled | 50 | 48 | 6 | 2 | syntax 5, test-adequacy 2 | syntax.parse 10, test-adequacy.assertion-free-test 1, test-adequacy.missing-tests 1 |
| origin | 1 | 1 | 0 | 0 | none | none |
| snapshot | 7 | 7 | 0 | 0 | none | none |
