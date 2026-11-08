# True-positive corpus (objective breakage)

Curated fixtures that **must** hard-fail AIV when the matching gates are enabled. Use this to prove catch-rate on known defects (not Airflow volume).

## Cases

| Id | Defect | Gate / rule |
|----|--------|-------------|
| `tp-merge-conflict` | `<<<<<<<` markers in `.py` | `invariant.merge-conflict` |
| `tp-search-replace` | Aider SEARCH/REPLACE paste | `invariant.ai-edit-artifact` |
| `tp-elision` | `... rest of the existing code unchanged` | `invariant.ai-edit-artifact` |
| `tp-bad-java` | Unbalanced braces | `syntax.parse` |
| `tp-tautology-test` | `assertTrue(true)` | `invariant.placeholder-test` |
| `tp-aws-key` | Fake AKIA key on added line | `security.aws-key` |

Fixtures live under `fixtures/`. Config: `fixtures/.aiv/config.yaml` (invariant + syntax + security on).

## Run

```powershell
mvn -pl aiv-cli -am package -DskipTests
java -jar aiv-cli/target/aiv-cli-1.0.4.jar --workspace benchmarks/true-positive/fixtures --diff HEAD
```

For a clean git diff, init the fixtures folder as a tiny repo or copy files into a branch. The labeled-50 bench remains the primary precision proof; this corpus documents the defect classes we hard-block.
