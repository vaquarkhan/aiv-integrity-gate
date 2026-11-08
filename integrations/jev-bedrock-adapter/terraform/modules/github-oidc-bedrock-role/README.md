# GitHub OIDC role for the AIV JEV Bedrock adapter

This module creates a least-privilege IAM role that a precisely identified GitHub Actions workflow can assume through OIDC. The role can invoke only the exact Amazon Bedrock resources supplied by the caller.

It creates no compute, network, storage, secret, or long-lived credential. The JEV adapter continues to run on the GitHub runner at `127.0.0.1`.

## Security defaults

- Reuses an existing account-level GitHub OIDC provider by default.
- Uses `StringEquals` for the audience and identity claim.
- Rejects wildcard GitHub identity values.
- Rejects wildcard Bedrock resource ARNs.
- Grants only `bedrock:InvokeModel`; the adapter does not stream.
- Restricts requests to explicit source Regions.
- Supports an IAM permissions boundary.
- Does not accept or output credentials.

## Inputs

| Input | Required | Purpose |
|---|---:|---|
| `github_identity_values` | yes | Exact accepted `sub` or `job_workflow_ref` values |
| `bedrock_resource_arns` | yes | Exact model and inference-profile resources |
| `allowed_bedrock_regions` | yes | Regions from which invocation may be requested |
| `existing_github_oidc_provider_arn` | normally | Existing account-level GitHub OIDC provider |
| `create_github_oidc_provider` | no | Opt in only when no provider exists |
| `github_oidc_thumbprints` | create mode | Reviewed CA thumbprints |
| `permissions_boundary_arn` | no | Organization policy boundary |

Cross-Region inference profiles can require both the inference-profile ARN and each destination foundation-model ARN. Supply all required resources explicitly. Do not replace them with wildcards.

Prefer a protected GitHub environment identity for pre-merge Bedrock access. GitHub may use immutable subject claims. Obtain the exact claim from the trusted workflow and provide it unchanged rather than constructing it from user-specific values.

See the production environment under `../../environments/prod/`.
