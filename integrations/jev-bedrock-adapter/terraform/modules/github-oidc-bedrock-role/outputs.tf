output "github_oidc_provider_arn" {
  description = "GitHub Actions OIDC provider used by the role."
  value       = local.github_oidc_provider_arn
}

output "bedrock_role_arn" {
  description = "IAM role ARN for the GitHub Actions credential step."
  value       = aws_iam_role.jev_bedrock.arn
}

output "bedrock_role_name" {
  description = "IAM role name."
  value       = aws_iam_role.jev_bedrock.name
}

output "trusted_github_identity_claim" {
  description = "GitHub OIDC identity claim constrained by the trust policy."
  value       = var.github_identity_claim
}

output "trusted_github_identity_values" {
  description = "Exact GitHub OIDC identity values trusted by the role."
  value       = sort(tolist(var.github_identity_values))
}

output "authorized_bedrock_resource_arns" {
  description = "Exact Bedrock resources the role may invoke."
  value       = sort(tolist(var.bedrock_resource_arns))
}
