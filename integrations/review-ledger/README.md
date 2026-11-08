# AIV review ledger

A small, standard-library Python tool that turns real review outcomes into evidence for changing
AIV policy. It records what AIV found on each PR head and what AI reviewers commented, then reports:

- **Rules authors act on.** An advisory rule whose findings are almost always fixed before merge is a
  candidate to promote to `fail`. One whose findings are almost never acted on is a candidate to turn
  off or narrow.
- **Rules that did not hold.** A blocking rule still present on the final head of a merged PR means a
  bypass, an override or a rule that was switched off. Investigate.
- **What AI reviewers find that AIV does not.** AI comments that were acted on (GitHub marks them
  outdated because the line changed) on files where AIV had no finding. This is where to look for the
  next deterministic rule. A human writes that rule and its tests.

Proposals use 95% Wilson intervals and a minimum sample (`--min-prs`, default 10), so a handful of PRs
never triggers a policy change. The report proposes; a maintainer decides and edits `.aiv/config.yaml`.

## Signals, and what they are not

| Signal | Meaning | Limitation |
|---|---|---|
| acted on | finding present on an earlier head of a merged PR, absent on the final head | the author may have removed the code for another reason |
| merged with | finding still present on the final head of a merged PR | needs an AIV run recorded on the final head; PRs without one are counted and skipped |
| outdated | GitHub marks the AI comment outdated (the line changed later) | a line can change without addressing the comment |
| downvoted / upvoted | thumbs reactions on the AI comment | most comments get no reaction |

Closed, unmerged PRs are not used as evidence.

## Usage

```bash
L=ledger/aiv-review-ledger.jsonl
# after each AIV run on a PR head
python aiv_review_ledger.py record-aiv   --ledger $L --pr 123 --sha $HEAD_SHA --aiv-report aiv-report.json
# when the PR closes
python aiv_review_ledger.py collect-ai   --ledger $L --pr 123 --repo owner/name   # needs GITHUB_TOKEN
python aiv_review_ledger.py record-final --ledger $L --pr 123 --sha $HEAD_SHA --merged true
# any time
python aiv_review_ledger.py report --ledger ledger/ --format md > ledger-report.md
```

`--ledger` for `report` accepts files or directories of `*.jsonl` and can repeat, so per-PR artifacts
can be downloaded into one folder and reported together. `record-ai` reads a saved
`pulls/{n}/comments` payload instead of calling GitHub. AI reviewer logins default to Copilot,
CodeRabbit, Gemini Code Assist and Claude; override with `--ai-reviewers`.

The example workflow `review-ledger-example.yml` records one row file per event and uploads it as an
artifact. Where to keep the ledger long term (an artifact store, a data branch, object storage) is the
adopter's choice; the tool only appends lines.

Text quoted from pull requests is sanitised (control, zero-width, bidi and tag characters removed,
pipes replaced, length bounded) and labelled as data in the report.

## Tests

```bash
python -m pytest integrations/review-ledger
```
