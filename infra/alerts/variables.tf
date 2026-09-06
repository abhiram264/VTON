# Terraform code only — apply prohibited for slice-68.

variable "alert_name_prefix" {
  description = "Prefix prepended to alert topic and CloudWatch alarm names."
  type        = string
  default     = "aiv"
}

variable "metric_namespace" {
  description = "CloudWatch custom metric namespace for application alarms."
  type        = string
  default     = "AI-Fashion-Vault"
}

variable "environment" {
  description = "Deployment environment name used in resource tags."
  type        = string
  default     = "prod"
}

variable "alb_arn_suffix" {
  description = "ARN suffix of the Application Load Balancer in the format app/<name>/<id>."
  type        = string
}

variable "alb_target_group_arn_suffix" {
  description = "ARN suffix of the ALB Target Group in the format targetgroup/<name>/<id>."
  type        = string
}

variable "ecs_cluster_name" {
  description = "Name of the ECS cluster hosting the API service."
  type        = string
}

variable "ecs_api_service_name" {
  description = "Name of the ECS API service for live task monitoring."
  type        = string
}

variable "rds_db_instance_identifier" {
  description = "Database instance identifier of the RDS PostgreSQL instance."
  type        = string
}

variable "generation_queue_name" {
  description = "QueueName dimension of the SQS generation work queue."
  type        = string
}

variable "media_bucket_name" {
  description = "Name of the S3 media bucket for 5XX request metrics and alerting."
  type        = string
}

variable "operator_emails_p0" {
  description = "Operator email addresses subscribed to P0 critical alert notifications."
  type        = list(string)
  default     = []
}

variable "operator_emails_p1" {
  description = "Operator email addresses subscribed to P1 warning alert notifications."
  type        = list(string)
  default     = []
}

variable "operator_emails_p2" {
  description = "Operator email addresses subscribed to P2 informational alert notifications."
  type        = list(string)
  default     = []
}

variable "source_resolver_ids" {
  description = "List of source resolver identifiers to create P2 degraded import alarms for."
  type        = list(string)
}

variable "garment_categories" {
  description = "List of garment categories to create P2 degraded quality alarms for."
  type        = list(string)
  default     = ["TOP", "BOTTOM", "ONE_PIECE", "ETHNIC_FULL_BODY", "SAREE", "FOOTWEAR"]
}

variable "notification_failed_outcome" {
  description = "Outcome dimension value indicating a push notification failure."
  type        = string
  default     = "FAILED"
}

variable "resolver_failure_status" {
  description = "Status dimension value indicating a source resolver import failure."
  type        = string
  default     = "FAILED"
}

variable "p0_alb_5xx_count" {
  description = "Threshold for ALB 5XX error count within a 300s window."
  type        = number
  default     = 50
}

variable "p0_auth_failure_ratio" {
  description = "Threshold for authentication failure ratio within a 300s window."
  type        = number
  default     = 0.5
}

variable "p0_generation_failure_ratio" {
  description = "Threshold for generation failure ratio within a 300s window."
  type        = number
  default     = 0.8
}

variable "p1_provider_error_count" {
  description = "Threshold for external provider error count within a 300s window."
  type        = number
  default     = 20
}

variable "p1_queue_oldest_age_ms" {
  description = "Threshold for oldest message age in generation queue in milliseconds."
  type        = number
  default     = 300000
}

variable "p1_sqs_backlog_depth" {
  description = "Threshold for ApproximateNumberOfMessagesVisible in generation queue."
  type        = number
  default     = 500
}

variable "p1_quality_reject_ratio" {
  description = "Threshold for overall generation quality reject ratio within a 300s window."
  type        = number
  default     = 0.15
}

variable "p1_push_failure_count" {
  description = "Threshold for push notification send failure count within a 300s window."
  type        = number
  default     = 10
}

variable "p1_hourly_cost_usd" {
  description = "Threshold for generation cost attempt sum in USD within a 3600s window."
  type        = number
  default     = 50
}

variable "p1_provider_credit_low" {
  description = "Minimum threshold for provider credit balance gauge."
  type        = number
  default     = 100
}

variable "p2_resolver_failure_ratio" {
  description = "Threshold for per-resolver import failure ratio within a 3600s window."
  type        = number
  default     = 0.25
}

variable "p2_category_quality_reject_ratio" {
  description = "Threshold for per-category quality reject ratio within a 3600s window."
  type        = number
  default     = 0.25
}
