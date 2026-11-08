variable "aws_region" {
  description = "AWS Region used by Terraform. The adapter should invoke Bedrock from an allowed Region."
  type        = string

  validation {
    condition     = can(regex("^[a-z]{2}(-gov)?-[a-z]+-[0-9]+$", var.aws_region))
    error_message = "aws_region must be a valid AWS Region."
  }
}

variable "role_name" {
  description = "Name of the GitHub Actions Bedrock role."
  type        = string
  default     = "aiv-jev-bedrock-github"
}

variable "create_github_oidc_provider" {
  description = "Create the account-level GitHub OIDC provider. Prefer reusing an existing provider."
  type        = bool
  default     = false
}

variable "existing_github_oidc_provider_arn" {
  description = "Exact ARN of an existing GitHub Actions IAM OIDC provider."
  type        = string
  default     = null
  nullable    = true
}

variable "github_oidc_thumbprints" {
  description = "Reviewed GitHub OIDC CA thumbprints, used only when creating the provider."
  type        = set(string)
  default     = []
}

variable "github_identity_claim" {
  description = "GitHub OIDC identity claim to match exactly: sub or job_workflow_ref."
  type        = string
  default     = "sub"
}

variable "github_identity_values" {
  description = "Exact trusted values for the GitHub identity claim."
  type        = set(string)
}

variable "bedrock_resource_arns" {
  description = "Exact Bedrock model/profile ARNs. No wildcard is accepted."
  type        = set(string)
}

variable "allowed_bedrock_regions" {
  description = "Regions from which Bedrock invocation may be requested."
  type        = set(string)
}

variable "max_session_duration" {
  description = "Maximum IAM role session duration in seconds."
  type        = number
  default     = 3600
}

variable "permissions_boundary_arn" {
  description = "Optional organization permissions-boundary policy ARN."
  type        = string
  default     = null
  nullable    = true
}

variable "tags" {
  description = "Organization tags applied to resources."
  type        = map(string)
  default = {
    Environment = "production"
    Application = "aiv-jev"
  }
}
