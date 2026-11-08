# Production GitHub OIDC and Bedrock role

This root module configures the IAM trust needed by the AIV JEV adapter on a trusted GitHub Actions runner. It does not deploy the adapter or store AWS credentials.

## Prerequisites

- A dedicated AWS account or production account approved for this workload.
- A remotely managed Terraform state bucket with encryption, versioning, access logging, public-access blocking, and locking.
- An approved Amazon Bedrock model or inference profile.
- A protected GitHub environment such as `jev-review`.
- An exact GitHub OIDC `sub` or `job_workflow_ref` value.
- An existing GitHub OIDC provider ARN, unless an authorized IAM administrator explicitly chooses create mode.

## Configure

```bash
copy terraform.tfvars.example terraform.tfvars
copy backend.hcl.example backend.hcl
```

Replace all placeholders. The copied files are ignored by Git. Never store AWS credentials, personal profile names, account IDs, or private backend names in committed files.

For the default model in the adapter, resolve the exact Bedrock inference-profile/model ARNs available in the chosen Region. Cross-Region inference can require the profile ARN plus each destination foundation-model ARN. This module intentionally rejects wildcard resources.

## Initialize and validate

```bash
terraform init -backend-config=backend.hcl
terraform fmt -check
terraform validate
terraform plan -out=jev-prod.tfplan
terraform show jev-prod.tfplan
```

The plan must contain only:

- optionally one GitHub IAM OIDC provider;
- one IAM role;
- one inline policy allowing `bedrock:InvokeModel` for exact resources and Regions.

Stop if the plan contains a wildcard principal, action, resource, an unexpected trust identity, or any resource outside this list.

## Apply

Applying changes AWS resources and is intentionally not automated by this repository. An authorized operator must review the plan and explicitly run:

```bash
terraform apply jev-prod.tfplan
```

Store `bedrock_role_arn` as an environment-level GitHub variable such as `AWS_ROLE_ARN`. Store the selected model/profile ID and Region as non-secret environment variables. Configure the GitHub environment's required reviewers and deployment restrictions.

## GitHub workflow boundary

The job requesting the OIDC token must declare:

```yaml
permissions:
  contents: read
  id-token: write
```

Add `pull-requests: write` or `checks: write` only when that same job publishes those surfaces. The role trust must match the exact protected environment or immutable workflow identity.

Do not use `pull_request_target` to check out and execute untrusted contributor code. The current JEV adapter is suitable for a controlled runner or a trusted workflow that treats the proposed diff as data.

## Destroy

Destroying the role interrupts JEV Bedrock access. Never destroy production infrastructure without an approved change and impact review.
