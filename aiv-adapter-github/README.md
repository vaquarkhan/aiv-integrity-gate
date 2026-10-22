# Module: `aiv-adapter-github`

This module turns `AIVResult` into human terminal output and GitHub-native review surfaces: Checks, inline comments, labels, and one deterministic findings comment.

## Architecture role

```text
AIVResult -> stdout
          -> GitHub Check + annotations
          -> PR review comments
          -> advisory / selected-finding labels
          -> one managed findings comment
```

It depends on `aiv-api` and Java HTTP facilities. It does not compute diffs, load policy, or evaluate gates. `aiv-cli` decides which publisher runs and supplies trusted publication configuration.

## Publishers

### `StdoutReportPublisher`

Prints overall status, notices, gate status/severity, messages, and structured findings. Quiet mode suppresses the human report for machine-only calls. Doctor mode is clearly labeled. Logging belongs on stderr; machine JSON remains clean stdout in `aiv verify`.

### `GithubChecksPublisher`

Creates a completed GitHub Check with success/failure conclusion, summary, notices, and up to 50 file annotations. Requires token, `owner/repo`, and SHA. The cap follows the single-request API bound; the result summary remains complete even when annotation count is capped.

### `GithubPrReviewCommentsPublisher`

Posts line comments for eligible structured findings and adds rule-specific remediation hints. Requires PR number and write permission. Findings without a usable path/line are not posted as inline comments.

### `GithubPrLabelPublisher`

Manages two label styles:

- advisory label when configured warning gates have findings,
- selected deterministic label (default `AI-SLOP`) based on configured gate/rule families.

The selected label describes findings only and never infers authorship. Labels are removed when findings clear.

### `GithubPrDeterministicFindingsPublisher`

Creates or updates one marker comment containing selected findings, stable ordering, severity, locations, remediation hints, and any `[AUDIT]` anchor. It renders at most 40 findings in the comment and removes the managed comment when the result clears.

## Required environment

| Variable | Purpose |
|----------|---------|
| `GITHUB_TOKEN` | API authentication |
| `GITHUB_REPOSITORY` | `owner/repo` |
| `GITHUB_SHA` | Check/comment commit |
| `AIV_GITHUB_PR_NUMBER` | pull request number |
| `GITHUB_EVENT_PATH` | fallback PR metadata source |
| `AIV_PR_ADVISORY_LABEL` | advisory label override |
| `AIV_AI_SLOP_LABEL` | selected-finding label override |

The composite Action maps GitHub context into these values. Fork PR tokens may be read-only; explicit publication reports permission errors instead of affecting the verdict silently.

## Trust boundary

`Main` passes the same resolved, frozen base-ref configuration to verdict and publication selection. A pull request cannot alter `ai_slop_label_gates`, `ai_slop_label_rules`, or label/comment enablement by editing workspace config.

## Example

```bash
java -jar aiv-cli.jar \
  --workspace . --diff origin/main --head HEAD \
  --require-trusted-config \
  --publish-github-checks \
  --publish-pr-comments \
  --label-pr-on-advisory aiv:review \
  --comment-ai-slop
```

In a workflow, grant only the permissions used:

```yaml
permissions:
  contents: read
  checks: write
  pull-requests: write
```

## Output and error semantics

Network calls use bounded Java HTTP clients and parse GitHub responses. Explicitly requested publication failures map to CLI integration exit 2; they do not rewrite gate findings. Config-requested publication is skipped on local runs without a GitHub context and logged clearly.

Severity is preserved: `blocksCi()` determines Check conclusion and presentation. Advisory findings remain review signals.

## Adding a publisher

Implement `ReportPublisher` for a whole-result surface or add a focused static publisher following these rules:

1. Consume `AIVResult`; never rerun gates.
2. Preserve stable rule ids and severity.
3. Cap remote payload size/count.
4. Keep credentials in environment, never config/report text.
5. Make repeated calls idempotent where possible.
6. Test with a local HTTP server, not live GitHub.

## Tests and build

- `StdoutReportPublisherTest`: pass/fail/advisory/doctor/quiet/notices.
- `GithubChecksPublisherTest`: payloads, annotations, caps, errors.
- `GithubPrReviewCommentsPublisherTest`: paths, hints, metadata, permissions.
- `GithubPrLabelPublisherTest`: add/remove/selection.
- `GithubPrDeterministicFindingsPublisherTest`: managed comment lifecycle, sorting, audit anchors.

```bash
mvn -pl aiv-adapter-github test
mvn -pl aiv-adapter-github verify
```

Tests use local HTTP servers and injected endpoints; no repository token or network access is required.
