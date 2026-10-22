# Proposal pilot: last 50 KIPs and last 30 FLIPs (October 2026)

`aiv proposal scan` run against the public Confluence spaces of Apache Kafka and Apache Flink, with the space index (number checks) and one shared audit chain. Reproduce with `run_pilot.cmd` from a directory next to the CLI jar; results are under `results/`.

## Calibration before these numbers

The first run produced findings that a reader would reject. Each one was checked against the page and the template or parser was fixed, not the number:

| First-run finding | Cause | Fix |
|---|---|---|
| Missing sections on KIP-1336 | Numbered headings ("1 Motivation") | Leading section numbers are ignored when matching headings. |
| All sections missing on FLIP-614 | Content in a Confluence `markdown` macro (CDATA) | Markdown inside the macro is parsed. |
| Missing "Public Interfaces" on KIP-1365 | Heading "New or Changed Public Interfaces" | The heading pattern matches the phrase anywhere in the heading. |
| Missing "Rejected Alternatives" on FLIP-614 and KIP-1359 | "Rejected and Deferred Alternatives"; a heading drawn as a large-font paragraph | Wider heading pattern; bold-only and large-font paragraphs count as headings. |
| Missing "Proposed Changes" on KIP-1335 | Heading "Detailed design" | Alias added. |
| Rejected Alternatives "None." and Test Plan "Unit tests." flagged as empty | Minimum word counts too high | Minimums lowered; only an empty heading is flagged now. |
| `XXX` placeholder in a code sample | Case-insensitive match | Placeholders are uppercase only. |
| 20 of 30 FLIPs "missing state" | Flink rarely adds the state label its template asks for | Off for the FLIP template (`report_missing_state: false`). |

## Result after calibration

| Space | Scanned | With findings | Findings by rule (proposals) |
|---|---|---|---|
| KAFKA (KIP) | 50 | 9 | missing-section 3, template-text 2, empty-section 2, placeholder 1, assistant-artifact 1, unknown-state 1 |
| FLINK (FLIP) | 30 | 7 | missing-section 3, number-collision 2 (one pair of pages), empty-section 1, placeholder 1 |

Every remaining finding was checked against the page text:

- KIP-1345: every section contains only the word "Thinking...", text left by a chat assistant (`assistant-artifact`, `empty-section`).
- KIP-1378, KIP-1375: the template's instruction sentence is still in "Proposed Changes" / "Public Interfaces" (`template-text`).
- KIP-1343: state is still the template text `[One of "Under Discussion", "Accepted", "Rejected"]` (`unknown-state`).
- KIP-1360: `TBD` ("API Key TBD", a number not yet assigned) and `TODO` in a draft (`placeholder`). Accurate, and expected in a draft.
- KIP-1336, KIP-1354, KIP-1356, FLIP-592, FLIP-594, FLIP-600: the named sections do not exist on the page.
- KIP-1363, FLIP-599: "Rejected Alternatives" heading with nothing under it.
- FLIP-613: two pages use the number 613 ("... - detailed" and the original).
- FLIP-597: "FLIP- TBD" in a table referring to a future proposal. Accurate, and arguably expected.

Two of the 16 flagged proposals (KIP-1360, FLIP-597) are accurate but low value: a draft is allowed to say TBD. The placeholder rule is the noisiest one for drafts; a project that agrees can remove the `placeholder` pattern from a forked template (`--template-file`). There is no per-state switch yet.

## What this shows and what it does not

It shows the checks are precise on current pages after one calibration pass: no finding that the page contradicts, out of 80 proposals. It does not show that these findings save a shepherd measurable time, and the calibration was done on the same 80 pages, so the next 80 will find new heading variants. Treat it as a pilot, not a precision estimate.

The chain verified intact (80 records). `results/report/proposals.html` is the evidence report built from it.
