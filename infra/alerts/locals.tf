# Terraform code only — apply prohibited for slice-68.

locals {
  alarm_prefix = var.alert_name_prefix

  tags = {
    Environment = var.environment
    Project     = "ai-fashion-vault"
    ManagedBy   = "terraform"
  }
}
