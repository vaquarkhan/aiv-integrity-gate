output "github_oidc_provider_arn" {
  description = "GitHub OIDC provider used by the role."
  value       = module.github_jev_bedrock_role.github_oidc_provider_arn
}

output "bedrock_role_arn" {
  description = "Store this value as the protected GitHub environment variable AWS_ROLE_ARN."
  value       = module.github_jev_bedrock_role.bedrock_role_arn
}

output "bedrock_role_name" {
  description = "Created IAM role name."
  value       = module.github_jev_bedrock_role.bedrock_role_name
}

output "trusted_github_identity_claim" {
  description = "GitHub identity claim constrained by the role."
  value       = module.github_jev_bedrock_role.trusted_github_identity_claim
}

output "trusted_github_identity_values" {
  description = "Exact GitHub identities trusted by the role."
  value       = module.github_jev_bedrock_role.trusted_github_identity_values
}

output "authorized_bedrock_resource_arns" {
  description = "Exact Bedrock resources authorized by the role."
  value       = module.github_jev_bedrock_role.authorized_bedrock_resource_arns
}
