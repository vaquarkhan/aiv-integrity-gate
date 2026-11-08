locals {
  github_oidc_url = "https://token.actions.githubusercontent.com"
  github_oidc_provider_arn = var.create_github_oidc_provider ? (
    aws_iam_openid_connect_provider.github[0].arn
  ) : var.existing_github_oidc_provider_arn

  common_tags = merge(
    {
      ManagedBy = "Terraform"
      Component = "aiv-jev-bedrock"
    },
    var.tags
  )
}

resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 1 : 0

  url             = local.github_oidc_url
  client_id_list  = [var.github_oidc_audience]
  thumbprint_list = var.github_oidc_thumbprints
  tags            = local.common_tags

  lifecycle {
    precondition {
      condition     = length(var.github_oidc_thumbprints) > 0
      error_message = "github_oidc_thumbprints must be supplied when create_github_oidc_provider is true."
    }

    precondition {
      condition     = var.existing_github_oidc_provider_arn == null
      error_message = "Do not supply existing_github_oidc_provider_arn when creating the provider."
    }
  }
}

data "aws_iam_policy_document" "assume_role" {
  statement {
    sid     = "GitHubActionsOidc"
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = [var.github_oidc_audience]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:${var.github_identity_claim}"
      values   = sort(tolist(var.github_identity_values))
    }
  }
}

resource "aws_iam_role" "jev_bedrock" {
  name                 = var.role_name
  description          = var.role_description
  assume_role_policy   = data.aws_iam_policy_document.assume_role.json
  max_session_duration = var.max_session_duration
  permissions_boundary = var.permissions_boundary_arn
  tags                 = local.common_tags

  lifecycle {
    precondition {
      condition = var.create_github_oidc_provider || (
        var.existing_github_oidc_provider_arn != null && trimspace(var.existing_github_oidc_provider_arn) != ""
      )
      error_message = "Set existing_github_oidc_provider_arn when create_github_oidc_provider is false."
    }
  }
}

data "aws_iam_policy_document" "bedrock_invoke" {
  statement {
    sid       = "InvokeApprovedBedrockResources"
    effect    = "Allow"
    actions   = ["bedrock:InvokeModel"]
    resources = sort(tolist(var.bedrock_resource_arns))

    condition {
      test     = "StringEquals"
      variable = "aws:RequestedRegion"
      values   = sort(tolist(var.allowed_bedrock_regions))
    }
  }
}

resource "aws_iam_role_policy" "bedrock_invoke" {
  name   = "aiv-jev-invoke-approved-models"
  role   = aws_iam_role.jev_bedrock.id
  policy = data.aws_iam_policy_document.bedrock_invoke.json
}
