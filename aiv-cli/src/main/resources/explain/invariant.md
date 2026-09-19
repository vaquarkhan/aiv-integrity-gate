# Gate: `invariant`

## Why it exists

Hard-block objective defects that should never reach mainline: unresolved merge state, placeholder tokens, and high-precision **AI edit-artifacts** in source files.

## Rules

- `invariant.merge-conflict` — `<<<<<<<` / `=======` / `>>>>>>>` line markers.
- `invariant.placeholder` — `TBD` / `FIXME` / `XXX` (whole changed-file content today; prefer enabling carefully).
- `invariant.ai-edit-artifact` — elision / assistant chatter / SEARCH-REPLACE blocks in **code** extensions only (not Markdown/RST prose).

## Default behavior

Often **disabled** in starter configs so TBD/FIXME in existing code does not block. Enable when you want these hard checks.
