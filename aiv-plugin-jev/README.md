# `aiv-plugin-jev`

This module contributes the `jev` quality gate, the model-assisted boundary in
AIV. It sends bounded review context and configured yes or no questions to a
System One compatible endpoint. Its output is advisory by construction and is
never used to infer authorship.

## Architecture role

`JevGate` translates an `AIVContext` into a small decision-model request,
performs one HTTP exchange through a `Transport` seam, and converts probabilities
above a threshold into reviewer findings. Production transport uses the JDK
`HttpClient`; no model SDK is embedded.

Jev complements deterministic gates when a question requires judgment. It must
not be used as the sole enforcement mechanism for a rule that can be expressed
with design constraints, generic rules, or a trusted static analyzer.

## Execution flow

1. Parse up to 64 configured checks. With no checks, pass without network use.
2. Resolve endpoint, credential environment variable, model, threshold, size
   bound, and timeout.
3. Read the credential only from the named environment variable. An explicitly
   empty `api_key_env` permits an unauthenticated compatible endpoint.
4. Build state from the raw diff, changed paths, and pull request title and
   body. Metadata values take precedence over `AIV_PR_TITLE` and
   `AIV_PR_BODY`.
5. Truncate the raw diff after `max_diff_chars` and append a truncation marker.
6. POST to `<base_url>/v1/systemone`.
7. Read `answers.<check-id>.noul` as `P(yes)`.
8. Report probabilities at or above `report_at` as advisory findings. Print the
   selected model, optional input-token count, threshold, and returned answers
   in the summary.

## Rule IDs

Each configured check produces `jev.<check-id>`. Check IDs must begin with an
alphanumeric character and may otherwise contain alphanumeric characters,
underscores, or hyphens.

## Configuration and defaults

| Key | Default | Meaning |
|---|---|---|
| `checks` | Empty, gate off | Map of check IDs to questions or structured checks. |
| `base_url` | `https://api.typesafe.ai` | System One compatible API base URL. |
| `api_key_env` | `TYPESAFE_API_KEY` | Environment variable holding bearer credentials. Empty means no authentication. |
| `model` | `jev-latest` | Model sent in the request. |
| `report_at` | `0.75` | Minimum `P(yes)` for an advisory finding. |
| `max_diff_chars` | `60000` | Maximum raw diff characters sent. |
| `timeout_seconds` | `30` | Connect and request timeout. |

A check can be a question string or a mapping with `question`, optional `yes`,
and optional `no` criteria.

## Example

```yaml
- id: jev
  enabled: true
  severity: warn
  config:
    base_url: https://api.typesafe.ai
    api_key_env: TYPESAFE_API_KEY
    model: jev-latest
    report_at: 0.80
    max_diff_chars: 40000
    timeout_seconds: 30
    checks:
      mixed_concerns:
        question: Does this change combine unrelated concerns?
        yes: The diff contains independently reviewable changes.
        no: The changed files support one coherent purpose.
      unclear_failure_mode: Is the failure behavior unclear?
```

## Request and response boundary

The request is `POST <base_url>/v1/systemone` with JSON fields `model`, `state`,
and `questions`. State contains `diff`, `changed_files`, `pr_title`, and
`pr_body`. Each question uses type `noul`, instructions, and optional criteria
with `true` and `false` descriptions.

The response boundary reads `answers.<id>.noul`, optional top-level `model`, and
optional `usage.input_tokens`. Full changed-file contents are not sent as
separate fields, although content can be present in the raw diff.

## Advisory-only and failure behavior

Jev has no blocking result path. High probabilities return
`GateResult.advisory`; lower probabilities and missing individual answers
return pass with a visible summary. Gate-level `severity` is not read by this
implementation and cannot make Jev block.

A missing key, network or timeout failure, interrupted request, non-success HTTP
status, or invalid JSON returns a passing result whose summary starts with
`SKIPPED jev:`. Interruption restores the thread interrupt flag. This is
fail-open by design so model availability cannot stop delivery.

## Model, network, and privacy boundary

Enabling checks allows this module to send raw diff text up to the configured
character limit, changed-file names, pull request title and body, configured
questions, and model selection to `base_url`. The API key is sent as bearer
authorization unless `api_key_env` is empty. Review the destination's privacy,
retention, residency, and cost terms before enabling a remote host.

A local compatible endpoint can keep review data on the machine, but the plugin
does not verify that an endpoint is local or prevent that endpoint from making
further network requests. It also provides no domain allowlist, redaction,
retry, TLS pinning, token budget, or cost control beyond diff character
truncation.

## Inputs and outputs

Inputs are raw diff, changed paths, pull request metadata or environment
fallbacks, check configuration, and an environment-sourced credential. Output
is a pass or advisory `GateResult`, with findings attached to `(diff)` at line
1. The plugin does not write repository files.

## Interactions and extension points

Use deterministic gates first and add Jev for questions that need reviewer
judgment. Compatible model gateways can be selected with `base_url`. The
`Transport` seam supports protocol tests and alternative HTTP behavior without
changing gate logic. New response fields should remain optional so missing
telemetry cannot turn model advice into enforcement.

## Honest boundaries

The plugin trusts returned probabilities and does not validate that they are in
the range 0 through 1. Negative `max_diff_chars` and nonpositive timeout values
are not proactively validated. There is no retry. A non-success response can
place up to 200 collapsed characters of the response body in the skip summary.
The model can be wrong, inconsistent, unavailable, or sensitive to prompt and
context wording. Jev is not an authorship classifier and never provides a hard
verdict.

Repository tests use a fake transport and a local HTTP server. They do not call
the TypeSafe service, validate a hosted model's calibration, or establish field
accuracy.

## Tests and build

`JevGateTest` covers service loading, the off state, request shape,
authorization, truncation, threshold behavior, validation, skip outcomes,
interrupt restoration, and a real local JDK HTTP exchange.

From this module:

```shell
mvn test
```

From the parent reactor:

```shell
mvn -pl aiv-plugin-jev test
```

See also [developer configuration](../docs/DEVELOPER-CONFIGURATION.md) and
`aiv explain jev`.

**Author:** Viquar Khan
