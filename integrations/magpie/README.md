# AIV as a deterministic input to Apache Magpie

[Apache Magpie](https://magpie.apache.org/) gives a maintainer's AI agent skills for PR triage,
review drafts, mentoring and security handling, with a human confirming every action. Its
principles say gates are deterministic and outputs are probabilistic, and its triage classifier
already reads every PR's check runs. Magpie itself publishes no merge-blocking check.

AIV fills that slot: it is the deterministic check, and Magpie triage treats it like any other CI
context. **No Magpie code changes are needed**, only three rows of Magpie project configuration.

## Setup

1. Give the AIV job a distinctive name in your workflow, so the check run is easy to match:

   ```yaml
   jobs:
     aiv:
       name: AIV Integrity Gate
   ```

   If you also use the Copilot review workflow in this repository, set the repository variable
   `AIV_CHECK_NAME` to the same name.

2. Merge the rows in [`magpie-config/pr-management-config.md`](magpie-config/pr-management-config.md)
   into your Magpie `pr-management-config.md`:
   `real_ci_patterns` gains `^AIV Integrity Gate$`, `static_check_patterns` gains
   `AIV Integrity Gate`.

3. Add the row in
   [`magpie-config/pr-management-triage-ci-check-map.md`](magpie-config/pr-management-triage-ci-check-map.md)
   to your `pr-management-triage-ci-check-map.md`, above the catch-all row.

## What Magpie then does

| AIV result on the PR head | Magpie triage |
|---|---|
| Fails, other CI green | `static-check-failure` (row 12): proposes a code-fix comment for the author, linking the AIV docs. No rerun, because a deterministic failure would fail again. |
| Fails together with test failures | row 12b: code-fix comment, not a rerun |
| Passes and is the only real CI | counts as real CI, so the PR can reach `passing` |
| Missing | the PR is never `passing` on bot checks alone |

Quick-merge's gates (real CI green, no failed checks) also refuse a PR whose AIV check failed.
As always in Magpie, every proposal waits for a maintainer to confirm it.

## Proof

`test_magpie_with_aiv.py` loads the snippet files with Magpie's own config loader and runs Magpie's
own triage classifier on PR fixtures built with Magpie's own test helpers. It covers the four rows
above and shows why a short pattern such as `aiv` would wrongly match a check like `naive-tests`.

```bash
MAGPIE_ROOT=/path/to/apache-magpie python -m pytest integrations/magpie
```

Verified against apache/magpie commit `ccb53a3` (6 tests passed). The test is skipped when no
Magpie checkout is found.

## Combining with the other AIV bridges

- AIV's optional `review-routing` gate can label a PR `aiv:review-<tier>`. Magpie triage reads labels,
  so a maintainer can filter with Magpie's `label:` selector (for example `label:aiv:review-none` to
  find PRs that should get no AI review at all).
- AIV's `--output-fix-brief` writes the same findings as a Markdown brief a contributor can hand to their
  own assistant; Magpie's comment links to the docs, the brief carries the exact file and line.

Magpie is an Apache Software Foundation project; AIV is not. This integration is configuration only and
does not imply endorsement by either project.
