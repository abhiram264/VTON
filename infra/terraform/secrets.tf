# Terraform code only — apply prohibited for slice-55.

resource "aws_secretsmanager_secret" "fashn_api_key" {
  name        = "${local.name_prefix}-fashn-api-key"
  description = "Fashn VTON provider API key. Injected out-of-band at apply time (aws secretsmanager put-secret-value); JSON shape {\"FASHN_API_KEY\": \"<value>\"}. Never declared in Terraform code or state."
  tags        = local.tags
}
