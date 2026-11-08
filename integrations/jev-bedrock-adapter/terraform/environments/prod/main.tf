module "github_jev_bedrock_role" {
  source = "../../modules/github-oidc-bedrock-role"

  role_name                         = var.role_name
  create_github_oidc_provider       = var.create_github_oidc_provider
  existing_github_oidc_provider_arn = var.existing_github_oidc_provider_arn
  github_oidc_thumbprints           = var.github_oidc_thumbprints
  github_identity_claim             = var.github_identity_claim
  github_identity_values            = var.github_identity_values
  bedrock_resource_arns             = var.bedrock_resource_arns
  allowed_bedrock_regions           = var.allowed_bedrock_regions
  max_session_duration              = var.max_session_duration
  permissions_boundary_arn          = var.permissions_boundary_arn
  tags                              = var.tags
}
