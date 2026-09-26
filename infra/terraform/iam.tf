# Terraform code only — apply prohibited for slice-55.

resource "aws_iam_role" "execution" {
  name = "${local.name_prefix}-execution"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
        Action = "sts:AssumeRole"
      }
    ]
  })
  tags = local.tags
}

resource "aws_iam_role" "api_task" {
  name = "${local.name_prefix}-api-task"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
        Action = "sts:AssumeRole"
      }
    ]
  })
  tags = local.tags
}

resource "aws_iam_role" "worker_task" {
  name = "${local.name_prefix}-worker-task"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
        Action = "sts:AssumeRole"
      }
    ]
  })
  tags = local.tags
}

resource "aws_iam_role_policy" "execution_logs_policy" {
  name = "${local.name_prefix}-execution-logs"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ExecutionLogWrites"
        Effect = "Allow"
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = [
          "arn:aws:logs:${var.aws_region}:*:log-group:/ecs/${local.name_prefix}-api:*",
          "arn:aws:logs:${var.aws_region}:*:log-group:/ecs/${local.name_prefix}-worker:*"
        ]
      }
    ]
  })
}

resource "aws_iam_role_policy" "execution_secrets_policy" {
  name = "${local.name_prefix}-execution-secrets"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ExecutionSecretReads"
        Effect = "Allow"
        Action = "secretsmanager:GetSecretValue"
        Resource = [
          aws_db_instance.vault.master_user_secret[0].secret_arn,
          aws_secretsmanager_secret.fashn_api_key.arn
        ]
      }
    ]
  })
}

resource "aws_iam_role_policy" "api_task_policy" {
  name = "${local.name_prefix}-api-task"
  role = aws_iam_role.api_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "S3ObjectAccess"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:AbortMultipartUpload"
        ]
        Resource = [
          "${aws_s3_bucket.avatar.arn}/*",
          "${aws_s3_bucket.vault_assets.arn}/*",
          "${aws_s3_bucket.look.arn}/*",
          "${aws_s3_bucket.temp.arn}/*"
        ]
      },
      {
        Sid    = "S3BucketList"
        Effect = "Allow"
        Action = "s3:ListBucket"
        Resource = [
          aws_s3_bucket.avatar.arn,
          aws_s3_bucket.vault_assets.arn,
          aws_s3_bucket.look.arn,
          aws_s3_bucket.temp.arn
        ]
      },
      {
        Sid      = "GenerationQueueSend"
        Effect   = "Allow"
        Action   = "sqs:SendMessage"
        Resource = aws_sqs_queue.generation_work.arn
      },
      {
        Sid      = "AdotMetrics"
        Effect   = "Allow"
        Action   = "cloudwatch:PutMetricData"
        Resource = "*"
        Condition = {
          StringEquals = {
            "cloudwatch:namespace" = var.metric_namespace
          }
        }
      },
      {
        Sid    = "AdotTraces"
        Effect = "Allow"
        Action = [
          "xray:PutTraceSegments",
          "xray:PutTelemetryRecords"
        ]
        Resource = "*"
      },
      {
        Sid    = "AdotLogGroupCreate"
        Effect = "Allow"
        Action = "logs:CreateLogGroup"
        Resource = [
          aws_cloudwatch_log_group.api.arn
        ]
      },
      {
        Sid    = "AdotLogStreamWrites"
        Effect = "Allow"
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = [
          "${aws_cloudwatch_log_group.api.arn}:*"
        ]
      }
    ]
  })
}

resource "aws_iam_role_policy" "worker_task_policy" {
  name = "${local.name_prefix}-worker-task"
  role = aws_iam_role.worker_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "S3ObjectAccess"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:AbortMultipartUpload"
        ]
        Resource = [
          "${aws_s3_bucket.avatar.arn}/*",
          "${aws_s3_bucket.vault_assets.arn}/*",
          "${aws_s3_bucket.look.arn}/*",
          "${aws_s3_bucket.temp.arn}/*"
        ]
      },
      {
        Sid    = "S3BucketList"
        Effect = "Allow"
        Action = "s3:ListBucket"
        Resource = [
          aws_s3_bucket.avatar.arn,
          aws_s3_bucket.vault_assets.arn,
          aws_s3_bucket.look.arn,
          aws_s3_bucket.temp.arn
        ]
      },
      {
        Sid    = "GenerationQueueConsume"
        Effect = "Allow"
        Action = [
          "sqs:SendMessage",
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:ChangeMessageVisibility",
          "sqs:GetQueueAttributes",
          "sqs:GetQueueUrl"
        ]
        Resource = [
          aws_sqs_queue.generation_work.arn,
          aws_sqs_queue.generation_work_dlq.arn
        ]
      },
      {
        Sid      = "AdotMetrics"
        Effect   = "Allow"
        Action   = "cloudwatch:PutMetricData"
        Resource = "*"
        Condition = {
          StringEquals = {
            "cloudwatch:namespace" = var.metric_namespace
          }
        }
      },
      {
        Sid    = "AdotTraces"
        Effect = "Allow"
        Action = [
          "xray:PutTraceSegments",
          "xray:PutTelemetryRecords"
        ]
        Resource = "*"
      },
      {
        Sid    = "AdotLogGroupCreate"
        Effect = "Allow"
        Action = "logs:CreateLogGroup"
        Resource = [
          aws_cloudwatch_log_group.worker.arn
        ]
      },
      {
        Sid    = "AdotLogStreamWrites"
        Effect = "Allow"
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = [
          "${aws_cloudwatch_log_group.worker.arn}:*"
        ]
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "execution_base" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "api_task_ecs_exec_policy" {
  name = "${local.name_prefix}-api-task-ecs-exec"
  role = aws_iam_role.api_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "EcsExecChannels"
        Effect = "Allow"
        Action = [
          "ssmmessages:CreateControlChannel",
          "ssmmessages:CreateDataChannel",
          "ssmmessages:OpenControlChannel",
          "ssmmessages:OpenDataChannel"
        ]
        Resource = "*"
      }
    ]
  })
}

resource "aws_iam_role_policy" "worker_task_ecs_exec_policy" {
  name = "${local.name_prefix}-worker-task-ecs-exec"
  role = aws_iam_role.worker_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "EcsExecChannels"
        Effect = "Allow"
        Action = [
          "ssmmessages:CreateControlChannel",
          "ssmmessages:CreateDataChannel",
          "ssmmessages:OpenControlChannel",
          "ssmmessages:OpenDataChannel"
        ]
        Resource = "*"
      }
    ]
  })
}
