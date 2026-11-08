# Terraform for JEV on Amazon Bedrock

Local JEV testing does not need Terraform. Set an AWS profile, start the loopback adapter, and run AIV.

Terraform is used for the production GitHub-to-AWS trust boundary. It provisions or reuses a GitHub Actions OIDC provider and creates a least-privilege role for Bedrock inference. It does not deploy the JEV adapter; the adapter runs on the GitHub runner and uses the temporary credentials exported by the AWS credentials action.

## Layout

```text
terraform/
  modules/github-oidc-bedrock-role/  reusable least-privilege IAM module
  environments/prod/                 production root module and safe templates
```

## Safety

- No account ID, repository owner, email, AWS profile, credential, or personal path is stored in source.
- Real values belong in an untracked `terraform.tfvars` and `backend.hcl`, or in the deployment system.
- The example files contain placeholders only.
- The module rejects wildcard trust identities and wildcard Bedrock resources.
- Production state must use encrypted remote storage and locking.
- Run `plan` and review it before `apply`. This repository's validation workflow never applies infrastructure.

## Validate without AWS credentials

```bash
terraform fmt -check -recursive
terraform -chdir=modules/github-oidc-bedrock-role init -backend=false
terraform -chdir=modules/github-oidc-bedrock-role validate
terraform -chdir=modules/github-oidc-bedrock-role test
terraform -chdir=environments/prod init -backend=false
terraform -chdir=environments/prod validate
python validate_security.py
```

## Production deployment

Read `environments/prod/README.md`. An authorized operator must supply the remote backend and environment inputs, review `terraform plan`, and explicitly approve any apply. This module should default to reusing the centrally managed GitHub OIDC provider rather than creating a duplicate.
