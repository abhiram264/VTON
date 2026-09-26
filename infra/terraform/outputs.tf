# Terraform code only — apply prohibited for slice-55.

output "cluster_name" {
  description = "Name of the ECS cluster running the API and worker services."
  value       = aws_ecs_cluster.vault.name
}

output "api_service_name" {
  description = "Name of the API ECS service."
  value       = aws_ecs_service.api.name
}

output "worker_service_name" {
  description = "Name of the generation worker ECS service."
  value       = aws_ecs_service.worker.name
}

output "api_log_group_name" {
  description = "CloudWatch log group name of the API service."
  value       = aws_cloudwatch_log_group.api.name
}

output "worker_log_group_name" {
  description = "CloudWatch log group name of the worker service."
  value       = aws_cloudwatch_log_group.worker.name
}

output "generation_queue_name" {
  description = "Name of the SQS generation work queue."
  value       = aws_sqs_queue.generation_work.name
}

output "generation_dlq_queue_name" {
  description = "Name of the SQS generation work dead-letter queue."
  value       = aws_sqs_queue.generation_work_dlq.name
}

output "generation_queue_url" {
  description = "URL of the SQS generation work queue."
  value       = aws_sqs_queue.generation_work.id
}

output "avatar_bucket_name" {
  description = "Name of the private avatar S3 bucket."
  value       = aws_s3_bucket.avatar.id
}

output "vault_bucket_name" {
  description = "Name of the private vault assets S3 bucket."
  value       = aws_s3_bucket.vault_assets.id
}

output "look_bucket_name" {
  description = "Name of the private saved-look S3 bucket."
  value       = aws_s3_bucket.look.id
}

output "temp_bucket_name" {
  description = "Name of the temporary generation S3 bucket."
  value       = aws_s3_bucket.temp.id
}

output "db_instance_identifier" {
  description = "Identifier of the PostgreSQL RDS instance."
  value       = aws_db_instance.vault.identifier
}

output "db_endpoint_address" {
  description = "DNS endpoint address of the PostgreSQL RDS instance."
  value       = aws_db_instance.vault.address
}

output "rds_master_secret_arn" {
  description = "ARN of the managed master-user secret of the RDS instance."
  value       = aws_db_instance.vault.master_user_secret[0].secret_arn
}

output "fashn_api_key_secret_arn" {
  description = "ARN of the Secrets Manager secret holding the FASHN API key."
  value       = aws_secretsmanager_secret.fashn_api_key.arn
}

output "vpc_id" {
  description = "ID of the production VPC."
  value       = aws_vpc.vault.id
}

output "private_subnet_ids" {
  description = "IDs of the private subnets hosting ECS tasks and the database."
  value       = [for subnet in aws_subnet.private : subnet.id]
}

output "public_subnet_ids" {
  description = "IDs of the public subnets hosting the load balancer."
  value       = [for subnet in aws_subnet.public : subnet.id]
}

output "ecr_repository_url" {
  description = "Repository URL of the backend ECR repository."
  value       = aws_ecr_repository.backend.repository_url
}

output "api_otel_service_name" {
  description = "OpenTelemetry service name of the API service."
  value       = local.api_otel_service_name
}

output "worker_otel_service_name" {
  description = "OpenTelemetry service name of the worker service."
  value       = local.worker_otel_service_name
}

output "api_target_group_arn" {
  description = "ARN of the ALB target group in front of the API service."
  value       = aws_lb_target_group.api.arn
}

output "alerts_p0_topic_arn" {
  description = "ARN of the SNS topic receiving P0 critical alerts."
  value       = module.alerts.alerts_p0_topic_arn
}

output "alerts_p1_topic_arn" {
  description = "ARN of the SNS topic receiving P1 warning alerts."
  value       = module.alerts.alerts_p1_topic_arn
}

output "alerts_p2_topic_arn" {
  description = "ARN of the SNS topic receiving P2 informational alerts."
  value       = module.alerts.alerts_p2_topic_arn
}

output "p0_alarm_names" {
  description = "List of all P0 CloudWatch metric alarm names in order."
  value       = module.alerts.p0_alarm_names
}

output "p1_alarm_names" {
  description = "List of all P1 CloudWatch metric alarm names in order."
  value       = module.alerts.p1_alarm_names
}

output "p2_alarm_names" {
  description = "List of all P2 CloudWatch metric alarm names across resolver and category families."
  value       = module.alerts.p2_alarm_names
}
