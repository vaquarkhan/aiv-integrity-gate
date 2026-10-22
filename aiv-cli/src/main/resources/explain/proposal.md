# Rules: `proposal.*` (aiv proposal check / scan)
## Why it exists
Improvement proposals (KIP, FLIP, SPIP, ...) live outside git: Confluence pages, JIRA tickets, sometimes a linked Google Doc. Before a shepherd spends an hour reading one, a few things can be checked mechanically: are the template's required sections there and filled, is the template's own instruction text still in place, are there placeholders, does an accepted proposal link its mailing-list thread, is the proposal number unique, does it cite proposals that exist, and has the content changed since it was accepted. These checks are advisory. They do not judge the design and they do not determine authorship.
## Rules
- `proposal.missing-section`, `proposal.empty-section` (fewer words of its own than the template's minimum), `proposal.template-text` (an instruction sentence from the template), `proposal.placeholder` (TBD, TODO, FIXME, XXX, lorem ipsum).
- `proposal.missing-state`, `proposal.unknown-state`, `proposal.accepted-without-thread`, `proposal.accepted-without-issue` (only when the template requires it).
- `proposal.number-collision`, `proposal.unknown-reference` (need `--index`).
- `proposal.changed-after-acceptance` (needs `--audit-log` with an earlier record of the same page; the status section is excluded from the hash, so state and release edits do not count).
- `proposal.external-doc`: the content is in a document AIV cannot read and that is not archived on ASF infrastructure.
## When a finding is wrong
Templates are data (`--template-file`). If your project names a section differently, or has another state, change the template rather than the proposal. Shipped templates: `kip`, `flip`, `spip`.
