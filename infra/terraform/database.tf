# Terraform code only — apply prohibited for slice-55.

resource "aws_db_subnet_group" "vault" {
  name        = "${local.name_prefix}-db"
  description = "Database subnet group for PostgreSQL RDS instance."
  subnet_ids  = [for subnet in aws_subnet.private : subnet.id]

  tags = local.tags
}

resource "aws_db_instance" "vault" {
  identifier                  = "${local.name_prefix}-db"
  engine                      = "postgres"
  engine_version              = "18"
  instance_class              = var.db_instance_class
  allocated_storage           = var.db_allocated_storage_gb
  storage_type                = "gp3"
  db_name                     = var.db_name
  username                    = var.db_master_username
  manage_master_user_password = true
  db_subnet_group_name        = aws_db_subnet_group.vault.name
  vpc_security_group_ids      = [aws_security_group.rds.id]
  publicly_accessible         = false
  multi_az                    = false
  storage_encrypted           = true
  backup_retention_period     = var.db_backup_retention_days
  deletion_protection         = true
  skip_final_snapshot         = false
  final_snapshot_identifier   = "${local.name_prefix}-db-final"

  tags = local.tags
}
