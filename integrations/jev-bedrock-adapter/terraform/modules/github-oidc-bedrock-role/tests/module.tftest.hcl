mock_provider "aws" {}

override_data {
  target = data.aws_iam_policy_document.assume_role
  values = {
    json = <<-JSON
      {
        "Version": "2012-10-17",
        "Statement": [{
          "Effect": "Allow",
          "Action": "sts:AssumeRoleWithWebIdentity",
          "Principal": {"Federated": "mock-github-oidc-provider"}
        }]
      }
    JSON
  }
}

override_data {
  target = data.aws_iam_policy_document.bedrock_invoke
  values = {
    json = <<-JSON
      {
        "Version": "2012-10-17",
        "Statement": [{
          "Effect": "Allow",
          "Action": "bedrock:InvokeModel",
          "Resource": "arn:aws:bedrock:us-east-1::foundation-model/example.model-v1:0"
        }]
      }
    JSON
  }
}

run "create_provider_with_exact_subject" {
  command = plan

  variables {
    create_github_oidc_provider = true
    github_oidc_thumbprints     = ["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
    github_identity_claim       = "sub"
    github_identity_values      = ["repo:EXAMPLE_ORG/EXAMPLE_REPOSITORY:environment:jev-review"]
    bedrock_resource_arns       = ["arn:aws:bedrock:us-east-1::foundation-model/example.model-v1:0"]
    allowed_bedrock_regions     = ["us-east-1"]
  }

  assert {
    condition     = aws_iam_role.jev_bedrock.max_session_duration == 3600
    error_message = "The default session duration must remain one hour."
  }

  assert {
    condition     = length(data.aws_iam_policy_document.bedrock_invoke.statement[0].actions) == 1
    error_message = "The role must grant only the non-streaming InvokeModel action."
  }
}

run "reject_wildcard_github_identity" {
  command = plan

  variables {
    create_github_oidc_provider = true
    github_oidc_thumbprints     = ["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
    github_identity_values      = ["repo:EXAMPLE_ORG/*"]
    bedrock_resource_arns       = ["arn:aws:bedrock:us-east-1::foundation-model/example.model-v1:0"]
    allowed_bedrock_regions     = ["us-east-1"]
  }

  expect_failures = [var.github_identity_values]
}

run "reject_wildcard_bedrock_resource" {
  command = plan

  variables {
    create_github_oidc_provider = true
    github_oidc_thumbprints     = ["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
    github_identity_values      = ["repo:EXAMPLE_ORG/EXAMPLE_REPOSITORY:environment:jev-review"]
    bedrock_resource_arns       = ["arn:aws:bedrock:us-east-1::foundation-model/*"]
    allowed_bedrock_regions     = ["us-east-1"]
  }

  expect_failures = [var.bedrock_resource_arns]
}
