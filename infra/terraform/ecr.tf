# Terraform code only — apply prohibited for slice-55.

resource "aws_ecr_repository" "backend" {
  name                 = "ai-fashion-vault-backend"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = local.tags
}
