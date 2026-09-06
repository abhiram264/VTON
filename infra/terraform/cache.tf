# Terraform code only — apply prohibited for slice-55.

resource "aws_elasticache_serverless_cache" "vault" {
  name        = "${local.name_prefix}-cache"
  engine      = "valkey"
  description = "Managed Redis-compatible cache filling plan §55 'managed Redis'; no V1 application consumer (ADR-005: SQS owns the generation queue)."

  subnet_ids         = [for subnet in aws_subnet.private : subnet.id]
  security_group_ids = [aws_security_group.cache.id]

  tags = local.tags
}
