# pr-management-config.md: AIV rows to merge into your Magpie project config

Copy the two rows below into the configuration table of your project's
`.apache-magpie-overrides/pr-management-config.md` (or your personal
`.apache-magpie-local/` layer). Keep your existing tokens and add the AIV ones:
Magpie reads every backticked token in the second column.

| Key | Value |
|---|---|
| `real_ci_patterns` | `Tests` `Build` `^AIV Integrity Gate$` |
| `static_check_patterns` | `AIV Integrity Gate` |

- `real_ci_patterns` makes the AIV check count as real CI, so a PR whose only
  green context is a bot check is still never `passing`.
- `static_check_patterns` routes an AIV failure to Magpie's
  `static-check-failure` classification (row 12 or 12b): a code-fix comment for
  the author, not a rerun. AIV failures are deterministic, so a rerun would fail
  the same way.
- The pattern is matched as a case-insensitive substring in either direction, so
  use the full job name rather than a short fragment such as `aiv`, which would
  also match unrelated checks like `naive-tests`.
