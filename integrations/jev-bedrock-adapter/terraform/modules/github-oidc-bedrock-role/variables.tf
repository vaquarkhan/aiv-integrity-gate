variable "role_name" {
  description = "Name of the IAM role assumed by the trusted GitHub Actions identity."
  type        = string
  default     = "aiv-jev-bedrock-github"

  validation {
    condition     = can(regex("^[A-Za-z0-9+=,.@_-]{1,64}$", var.role_name))
    error_message = "role_name must satisfy the IAM role-name character and length limits."
  }
}

variable "role_description" {
  description = "Description attached to the IAM role."
  type        = string
  default     = "Least-privilege Bedrock inference role for the AIV JEV adapter"

  validation {
    condition     = length(var.role_description) >= 10 && length(var.role_description) <= 1000
    error_message = "role_description must contain 10 to 1000 characters."
  }
}

variable "create_github_oidc_provider" {
  description = "Create the account-level GitHub OIDC provider. Keep false when the account already has one."
  type        = bool
  default     = false
}

variable "existing_github_oidc_provider_arn" {
  description = "ARN of an existing token.actions.githubusercontent.com IAM OIDC provider. Required when create_github_oidc_provider is false."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition = var.existing_github_oidc_provider_arn == null || (
      !strcontains(var.existing_github_oidc_provider_arn, "*") &&
      !strcontains(var.existing_github_oidc_provider_arn, "?") &&
      can(regex("^arn:(aws|aws-us-gov|aws-cn):iam::[0-9]{12}:oidc-provider/token\\.actions\\.githubusercontent\\.com$", var.existing_github_oidc_provider_arn))
    )
    error_message = "existing_github_oidc_provider_arn must be an exact GitHub OIDC provider ARN with no wildcard."
  }
}

variable "github_oidc_thumbprints" {
  description = "Reviewed SHA-1 CA thumbprints used only when this module creates the OIDC provider."
  type        = set(string)
  default     = []

  validation {
    condition     = alltrue([for value in var.github_oidc_thumbprints : can(regex("^[0-9A-Fa-f]{40}$", value))])
    error_message = "Every GitHub OIDC thumbprint must contain exactly 40 hexadecimal characters."
  }
}

variable "github_oidc_audience" {
  description = "Audience required in the GitHub OIDC token."
  type        = string
  default     = "sts.amazonaws.com"

  validation {
    condition     = var.github_oidc_audience == "sts.amazonaws.com"
    error_message = "This module supports only the AWS STS audience."
  }
}

variable "github_identity_claim" {
  description = "Exact GitHub token claim AWS must match. IAM accepts sub or job_workflow_ref for GitHub Actions."
  type        = string
  default     = "sub"

  validation {
    condition     = contains(["sub", "job_workflow_ref"], var.github_identity_claim)
    error_message = "github_identity_claim must be sub or job_workflow_ref."
  }
}

variable "github_identity_values" {
  description = "Exact allowed values for github_identity_claim. Wildcards are rejected. Use a protected environment or immutable workflow identity for production."
  type        = set(string)

  validation {
    condition = length(var.github_identity_values) > 0 && alltrue([
      for value in var.github_identity_values :
      length(trimspace(value)) > 0 &&
      !strcontains(value, "*") &&
      !strcontains(value, "?")
    ])
    error_message = "Provide at least one exact, non-empty GitHub identity value without * or ?."
  }
}

variable "bedrock_resource_arns" {
  description = "Exact Bedrock model and inference-profile ARNs the adapter may invoke. Cross-Region profiles also require their destination foundation-model ARNs."
  type        = set(string)

  validation {
    condition = length(var.bedrock_resource_arns) > 0 && alltrue([
      for arn in var.bedrock_resource_arns :
      !strcontains(arn, "*") &&
      !strcontains(arn, "?") &&
      can(regex("^arn:(aws|aws-us-gov|aws-cn):bedrock:[a-z0-9-]*:[0-9]*:(foundation-model|inference-profile|application-inference-profile|provisioned-model|custom-model|imported-model)/[^*?]+$", arn))
    ])
    error_message = "Provide one or more exact Bedrock model/profile ARNs with an approved resource type and no wildcard."
  }
}

variable "allowed_bedrock_regions" {
  description = "AWS request Regions from which Bedrock InvokeModel is allowed."
  type        = set(string)

  validation {
    condition = length(var.allowed_bedrock_regions) > 0 && alltrue([
      for region in var.allowed_bedrock_regions : can(regex("^[a-z]{2}(-gov)?-[a-z]+-[0-9]+$", region))
    ])
    error_message = "Provide at least one valid AWS Region."
  }
}

variable "max_session_duration" {
  description = "Maximum IAM role session duration in seconds."
  type        = number
  default     = 3600

  validation {
    condition     = var.max_session_duration >= 3600 && var.max_session_duration <= 43200
    error_message = "max_session_duration must be between 3600 and 43200 seconds."
  }
}

variable "permissions_boundary_arn" {
  description = "Optional IAM permissions-boundary policy ARN for the role."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition = var.permissions_boundary_arn == null || (
      !strcontains(var.permissions_boundary_arn, "*") &&
      can(regex("^arn:(aws|aws-us-gov|aws-cn):iam::[0-9]{12}:policy/.+$", var.permissions_boundary_arn))
    )
    error_message = "permissions_boundary_arn must be an exact IAM policy ARN with no wildcard."
  }
}

variable "tags" {
  description = "Additional tags for IAM resources."
  type        = map(string)
  default     = {}
}
