# Terraform code only — apply prohibited for slice-55.

resource "aws_ecs_cluster" "vault" {
  name = local.name_prefix

  tags = local.tags
}

resource "aws_ecs_task_definition" "api" {
  family = "${local.name_prefix}-api"

  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.api_cpu
  memory                   = var.api_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.api_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "AMD64"
  }

  container_definitions = jsonencode([
    {
      name         = "app"
      essential    = true
      image        = var.backend_container_image
      portMappings = [{ containerPort = 8080, protocol = "tcp" }]
      environment = [
        { name = "APP_ENV", value = "production" },
        { name = "DB_URL", value = local.db_url },
        { name = "DB_USERNAME", valueFrom = "${aws_db_instance.vault.master_user_secret[0].secret_arn}:username::" },
        { name = "DB_PASSWORD", valueFrom = "${aws_db_instance.vault.master_user_secret[0].secret_arn}:password::" },
        { name = "AWS_REGION", value = var.aws_region },
        { name = "SQS_GENERATION_QUEUE_URL", value = aws_sqs_queue.generation_work.id },
        { name = "S3_AVATAR_BUCKET", value = aws_s3_bucket.avatar.id },
        { name = "S3_VAULT_BUCKET", value = aws_s3_bucket.vault_assets.id },
        { name = "S3_LOOK_BUCKET", value = aws_s3_bucket.look.id },
        { name = "S3_TEMP_BUCKET", value = aws_s3_bucket.temp.id },
        { name = "FASHN_API_KEY", valueFrom = "${aws_secretsmanager_secret.fashn_api_key.arn}:FASHN_API_KEY::" },
        { name = "FIREBASE_PROJECT_ID", value = var.firebase_project_id },
        { name = "OTEL_SERVICE_NAME", value = local.api_otel_service_name },
        { name = "OTEL_RESOURCE_ATTRIBUTES", value = "service.name=ai-fashion-vault-api,deployment.environment=${var.environment}" },
        { name = "OTEL_EXPORTER_OTLP_ENDPOINT", value = "http://127.0.0.1:4317" }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.api.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "ecs"
        }
      }
    },
    {
      name      = "aws-otel-collector"
      essential = false
      image     = "public.ecr.aws/aws-observability/aws-otel-collector:latest"
      command   = ["--config=/etc/ecs/default.yaml"]
      memory    = 256
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.api.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "adot"
        }
      }
    }
  ])

  tags = local.tags
}

resource "aws_ecs_task_definition" "worker" {
  family = "${local.name_prefix}-worker"

  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.worker_cpu
  memory                   = var.worker_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.worker_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "AMD64"
  }

  container_definitions = jsonencode([
    {
      name         = "app"
      essential    = true
      image        = var.backend_container_image
      portMappings = [{ containerPort = 8080, protocol = "tcp" }]
      environment = [
        { name = "APP_ENV", value = "production" },
        { name = "DB_URL", value = local.db_url },
        { name = "DB_USERNAME", valueFrom = "${aws_db_instance.vault.master_user_secret[0].secret_arn}:username::" },
        { name = "DB_PASSWORD", valueFrom = "${aws_db_instance.vault.master_user_secret[0].secret_arn}:password::" },
        { name = "AWS_REGION", value = var.aws_region },
        { name = "SQS_GENERATION_QUEUE_URL", value = aws_sqs_queue.generation_work.id },
        { name = "S3_AVATAR_BUCKET", value = aws_s3_bucket.avatar.id },
        { name = "S3_VAULT_BUCKET", value = aws_s3_bucket.vault_assets.id },
        { name = "S3_LOOK_BUCKET", value = aws_s3_bucket.look.id },
        { name = "S3_TEMP_BUCKET", value = aws_s3_bucket.temp.id },
        { name = "FASHN_API_KEY", valueFrom = "${aws_secretsmanager_secret.fashn_api_key.arn}:FASHN_API_KEY::" },
        { name = "FIREBASE_PROJECT_ID", value = var.firebase_project_id },
        { name = "OTEL_SERVICE_NAME", value = local.worker_otel_service_name },
        { name = "OTEL_RESOURCE_ATTRIBUTES", value = "service.name=ai-fashion-vault-worker,deployment.environment=${var.environment}" },
        { name = "OTEL_EXPORTER_OTLP_ENDPOINT", value = "http://127.0.0.1:4317" },
        { name = "VAULT_GENERATION_QUEUE_RELAYENABLED", value = "true" },
        { name = "VAULT_GENERATION_RECOVERY_ENABLED", value = "true" }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.worker.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "ecs"
        }
      }
    },
    {
      name      = "aws-otel-collector"
      essential = false
      image     = "public.ecr.aws/aws-observability/aws-otel-collector:latest"
      command   = ["--config=/etc/ecs/default.yaml"]
      memory    = 256
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.worker.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "adot"
        }
      }
    }
  ])

  tags = local.tags
}

resource "aws_ecs_service" "api" {
  name    = "api"
  cluster = aws_ecs_cluster.vault.id

  task_definition                   = aws_ecs_task_definition.api.arn
  launch_type                       = "FARGATE"
  desired_count                     = var.api_desired_count
  platform_version                  = "LATEST"
  health_check_type                 = "ALB"
  health_check_grace_period_seconds = 120
  enable_execute_command            = true

  network_configuration {
    subnets          = [for subnet in aws_subnet.private : subnet.id]
    security_groups  = [aws_security_group.api.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "app"
    container_port   = 8080
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  tags = local.tags
}

resource "aws_ecs_service" "worker" {
  name    = "worker"
  cluster = aws_ecs_cluster.vault.id

  task_definition        = aws_ecs_task_definition.worker.arn
  launch_type            = "FARGATE"
  desired_count          = var.worker_desired_count
  platform_version       = "LATEST"
  enable_execute_command = true

  network_configuration {
    subnets          = [for subnet in aws_subnet.private : subnet.id]
    security_groups  = [aws_security_group.worker.id]
    assign_public_ip = false
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  tags = local.tags
}
