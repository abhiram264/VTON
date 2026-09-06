# Terraform code only — apply prohibited for slice-55.

resource "aws_s3_bucket" "avatar" {
  bucket        = local.avatar_bucket_name
  force_destroy = false

  tags = local.tags
}

resource "aws_s3_bucket_public_access_block" "avatar" {
  bucket = aws_s3_bucket.avatar.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "avatar" {
  bucket = aws_s3_bucket.avatar.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket" "vault_assets" {
  bucket        = local.vault_bucket_name
  force_destroy = false

  tags = local.tags
}

resource "aws_s3_bucket_public_access_block" "vault_assets" {
  bucket = aws_s3_bucket.vault_assets.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "vault_assets" {
  bucket = aws_s3_bucket.vault_assets.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket" "look" {
  bucket        = local.look_bucket_name
  force_destroy = false

  tags = local.tags
}

resource "aws_s3_bucket_public_access_block" "look" {
  bucket = aws_s3_bucket.look.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "look" {
  bucket = aws_s3_bucket.look.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket" "temp" {
  bucket        = local.temp_bucket_name
  force_destroy = false

  tags = local.tags
}

resource "aws_s3_bucket_public_access_block" "temp" {
  bucket = aws_s3_bucket.temp.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "temp" {
  bucket = aws_s3_bucket.temp.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "temp" {
  bucket = aws_s3_bucket.temp.id

  rule {
    id     = "temp-safety-net"
    status = "Enabled"

    filter {}

    expiration {
      days = 1
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}
