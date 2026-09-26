# Terraform code only — apply prohibited for slice-55.

resource "aws_vpc" "vault" {
  cidr_block           = var.vpc_cidr
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = local.tags
}

resource "aws_subnet" "public" {
  for_each = { for idx, az in var.availability_zones : az => idx }

  vpc_id                  = aws_vpc.vault.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, each.value)
  availability_zone       = each.key
  map_public_ip_on_launch = true

  tags = merge(local.tags, {
    Name = "${local.name_prefix}-public-${each.key}"
    Tier = "public"
  })
}

resource "aws_subnet" "private" {
  for_each = { for idx, az in var.availability_zones : az => idx }

  vpc_id                  = aws_vpc.vault.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, each.value + 16)
  availability_zone       = each.key
  map_public_ip_on_launch = false

  tags = merge(local.tags, {
    Name = "${local.name_prefix}-private-${each.key}"
    Tier = "private"
  })
}

resource "aws_internet_gateway" "vault" {
  vpc_id = aws_vpc.vault.id

  tags = local.tags
}

resource "aws_eip" "nat" {
  domain = "vpc"

  tags = local.tags
}

resource "aws_nat_gateway" "primary" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public[var.availability_zones[0]].id

  depends_on = [
    aws_eip.nat,
    aws_internet_gateway.vault,
  ]

  tags = local.tags
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.vault.id

  tags = local.tags
}

resource "aws_route" "public" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.vault.id
}

resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.vault.id

  tags = local.tags
}

resource "aws_route" "private" {
  route_table_id         = aws_route_table.private.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.primary.id
}

resource "aws_route_table_association" "private" {
  for_each = aws_subnet.private

  subnet_id      = each.value.id
  route_table_id = aws_route_table.private.id
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.vault.id
  service_name      = "com.amazonaws.${var.aws_region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private.id]

  tags = local.tags
}

resource "aws_security_group" "alb" {
  name        = "${local.name_prefix}-alb"
  description = "Security group for public Application Load Balancer."
  vpc_id      = aws_vpc.vault.id

  ingress {
    description = "Allow inbound HTTP from internet"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "Allow inbound HTTPS from internet"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description     = "Allow outbound to API tasks"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.api.id]
  }

  tags = local.tags
}

resource "aws_security_group" "api" {
  name        = "${local.name_prefix}-api"
  description = "Security group for API tasks."
  vpc_id      = aws_vpc.vault.id

  egress {
    description = "Allow all outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = local.tags
}

resource "aws_security_group_rule" "api_ingress_from_alb" {
  description              = "Allow inbound HTTP from ALB"
  type                     = "ingress"
  security_group_id        = aws_security_group.api.id
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.alb.id
}

resource "aws_security_group" "worker" {
  name        = "${local.name_prefix}-worker"
  description = "Security group for worker tasks."
  vpc_id      = aws_vpc.vault.id

  egress {
    description = "Allow all outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = local.tags
}

resource "aws_security_group" "rds" {
  name        = "${local.name_prefix}-rds"
  description = "Security group for PostgreSQL RDS instance."
  vpc_id      = aws_vpc.vault.id

  ingress {
    description     = "Allow PostgreSQL inbound from API tasks"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.api.id]
  }

  ingress {
    description     = "Allow PostgreSQL inbound from worker tasks"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.worker.id]
  }

  egress {
    description = "Allow all outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = local.tags
}
