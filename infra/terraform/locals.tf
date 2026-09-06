# Terraform code only — apply prohibited for slice-55.

locals {
  name_prefix               = "ai-fashion-vault-${var.environment}"
  avatar_bucket_name        = "${local.name_prefix}-avatar-private"
  vault_bucket_name         = "${local.name_prefix}-vault-assets"
  look_bucket_name          = "${local.name_prefix}-look-private"
  temp_bucket_name          = "${local.name_prefix}-generation-temp"
  generation_queue_name     = "${local.name_prefix}-generation-work"
  generation_dlq_queue_name = "${local.name_prefix}-generation-work-dlq"
  api_otel_service_name     = "ai-fashion-vault-api"
  worker_otel_service_name  = "ai-fashion-vault-worker"
  tags                      = merge(var.tags, { Environment = var.environment })
  db_url                    = "jdbc:postgresql://${aws_db_instance.vault.address}:${aws_db_instance.vault.port}/${aws_db_instance.vault.db_name}"
}
