# JEV on Amazon Bedrock

`jev_bedrock_adapter.py` is a loopback-only System One endpoint for AIV's optional `jev` gate. It receives one JEV request at `POST /v1/systemone`, sends one Amazon Bedrock Converse request, and returns a calibrated `P(yes)` for every configured review question.

JEV is advisory only. Deterministic AIV gates remain the system of record. If Bedrock is unavailable, JEV returns `SKIPPED jev: <reason>` and the deterministic result remains unchanged.

## Local run

Local testing needs no Terraform. Install the reviewed dependency and use a least-privilege AWS profile that can invoke only the selected Bedrock model or inference profile:

```bash
python -m pip install --requirement requirements.txt
AWS_PROFILE=<approved-profile> python integrations/jev-bedrock-adapter/jev_bedrock_adapter.py \
  --model <approved-model-or-inference-profile-id> \
  --region us-east-1 \
  --port 8787
```

The adapter binds to `127.0.0.1` only. It accepts only the local `Host` header and `application/json`, reads at most 2 MiB per request and drops slow sockets after 30 seconds. Never expose it directly on a network.

For a shared developer machine, require a bearer token that is held only in an environment variable. Do not put it in a committed JEV config file:

```bash
export JEV_ADAPTER_TOKEN="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
python integrations/jev-bedrock-adapter/jev_bedrock_adapter.py \
  --model <approved-model-or-inference-profile-id> \
  --region us-east-1 \
  --api-key-env JEV_ADAPTER_TOKEN
```

Then set `api_key_env: JEV_ADAPTER_TOKEN` in the JEV gate. The normal loopback-only workflow may keep `api_key_env: ""` when no other local process can make requests.

## Configure the JEV gate

Commit the gate policy to the protected base branch before enabling a workflow that uses `--require-trusted-config`.

```yaml
- id: jev
  enabled: true
  severity: warn
  config:
    base_url: http://127.0.0.1:8787
    api_key_env: ""                # AWS credentials are used by the adapter, not JEV
    model: jev-bedrock
    report_at: 0.75
    max_diff_chars: 60000
    timeout_seconds: 30             # total client deadline; adapter defaults finish within it
    checks:
      description_mismatch:
        question: "Does the description claim things the diff does not do?"
        yes: "A concrete claim is unsupported by the diff."
        no: "The description matches the implemented change."
      unrelated_changes:
        question: "Does the diff include changes unrelated to the stated purpose?"
        yes: "A changed file or hunk serves another purpose."
        no: "Every change supports the stated purpose."
      unfinished_code:
        question: "Does the change present unfinished logic as complete?"
        yes: "There is a stub, placeholder, or fake production path."
        no: "The implementation is complete for what it claims."
      missing_why:
        question: "Is the motivation missing or generic?"
        yes: "No concrete reason, issue, or reproduction is given."
        no: "The description explains why the change is needed."
      unnecessary_code:
        question: "Does the change add code its purpose does not need?"
        yes: "The implementation is disproportionate or unnecessary."
        no: "The implementation is proportionate to its purpose."
      likely_broken:
        question: "Is there a concrete reason the change will not work?"
        yes: "The diff has a visible logic, API, signature, or missed-case problem."
        no: "No concrete breakage is visible."
```

The example catalog is [`jev-slop-checks.json`](jev-slop-checks.json). It is not loaded automatically. Copy or tailor its checks under `config.checks`.

## Request deadline and retry budget

The JEV gate adds `X-AIV-Deadline-Epoch-Ms` to each request. It defaults to a 30-second client deadline, leaving one second for loopback response delivery.

The adapter defaults are deliberately smaller:

| Setting | Default | Meaning |
|---|---:|---|
| `--connect-timeout-seconds` | 5 | Maximum Bedrock connection wait |
| `--request-timeout-seconds` | 20 | Maximum single Bedrock request wait |
| `--retry-max-attempts` | 1 | Total adapter-owned attempts, including the first |
| `--max-tokens` | 1200 | Bedrock response token ceiling |

The adapter disables the SDK retry loop and owns any optional retry budget itself. Before increasing `--retry-max-attempts`, increase JEV's `timeout_seconds` so all attempts, backoff, and response serialization still fit before the deadline. The adapter rejects expired or malformed deadlines and returns HTTP 502; JEV reports that as `SKIPPED`.

## Production GitHub Actions

For GitHub-hosted runners, do not store AWS access keys. Use GitHub OIDC and the least-privilege Terraform role under [`terraform/`](terraform/README.md).

```text
GitHub protected environment
  -> short-lived OIDC role
  -> loopback JEV adapter on the runner
  -> Bedrock Converse
```

The checked-in [`jev-bedrock-review.yml`](../../.github/workflows/jev-bedrock-review.yml) is a trusted workflow template. It:

1. runs only when `JEV_BEDROCK_ENABLED` is explicitly enabled;
2. checks out the protected base commit, not the pull request head;
3. fetches the pull request head only as Git objects, then passes its diff to AIV;
4. configures short-lived OIDC credentials after the trusted checkout;
5. starts and health-checks the loopback adapter;
6. writes a strict JSON evidence receipt, preserves the deterministic AIV exit code, and terminates the adapter.

Before enabling it, create a protected GitHub environment named `jev-review` and configure these non-secret environment variables:

| Variable | Value |
|---|---|
| `JEV_BEDROCK_ENABLED` | `true` after review |
| `JEV_BEDROCK_ROLE_ARN` | Terraform output `bedrock_role_arn` |
| `JEV_BEDROCK_MODEL_ID` | Approved model or inference-profile ID/ARN |
| `JEV_BEDROCK_REGION` | Approved invocation Region |
| `AIV_CLI_URL` | Immutable released AIV CLI JAR URL |

The OIDC trust policy must limit access to the exact protected environment or immutable workflow identity. Do not use `pull_request_target` to check out or run pull-request source code. This workflow treats the proposed diff as data and runs only the protected-base adapter and policy. Do not enable the `external` gate in that credentialed JEV policy because it can start configured commands after AWS credentials are issued. Run external tools in the ordinary untrusted AIV workflow before this advisory JEV step.

The trusted JEV workflow produces a strict JSON evidence receipt instead of SARIF because it evaluates a fetched pull-request diff through `aiv verify --request` with a protected policy reference. The standard pull-request Action in [GITHUB-QUICKSTART.md](../../docs/GITHUB-QUICKSTART.md) produces SARIF for code-scanning upload.

## Privacy, cost, and evidence

The adapter sends the diff, up to 300 changed file names, PR title, PR body capped at 8,000 characters, and configured questions to the selected Bedrock model in the active AWS account and Region. It does not log request content, credentials, raw AWS errors, account identifiers or model/role ARNs in HTTP responses. Review data classification, retention, Region, model access, and spending controls before enabling it.

`GET /stats` returns total calls, input tokens, output tokens, and model ID. Capture it as build evidence. The adapter does not store PR content or credentials.

## Tests

```bash
python integrations/jev-bedrock-adapter/test_jev_bedrock_adapter.py
python integrations/jev-bedrock-adapter/terraform/validate_security.py
```

The adapter unit test covers deadline rejection, bounded client use, token accounting, and response parsing. It does not invoke Bedrock.

Airflow evaluation: `benchmarks/airflow/scripts/run-jev-bedrock-eval.py`, results in `benchmarks/airflow/reports/jev-bedrock-eval-latest.md`.
