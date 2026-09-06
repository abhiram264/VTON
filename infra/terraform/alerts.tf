# Terraform code only — apply prohibited for slice-68.

module "alerts" {
  source = "../alerts"

  environment                 = var.environment
  alb_arn_suffix              = aws_lb.api.arn_suffix
  alb_target_group_arn_suffix = aws_lb_target_group.api.arn_suffix
  ecs_cluster_name            = aws_ecs_cluster.vault.name
  ecs_api_service_name        = aws_ecs_service.api.name
  rds_db_instance_identifier  = aws_db_instance.vault.identifier
  generation_queue_name       = aws_sqs_queue.generation_work.name
  media_bucket_name           = aws_s3_bucket.temp.id

  # Operators set severity email lists at apply time (spec default).
  operator_emails_p0 = []
  operator_emails_p1 = []
  operator_emails_p2 = []

  # Verbatim from ShareImportConfiguration resolver registrations.
  source_resolver_ids = ["SHARED_IMAGE", "GENERIC_OPEN_GRAPH", "GENERIC_URL"]

  # Spec default FAILED matches no code literal; NotificationMetrics outcomes are ACCEPTED/RETRY_SCHEDULED/PERMANENT_FAILURE/DEADLINE_EXCEEDED/ATTEMPTS_EXHAUSTED.
  notification_failed_outcome = "PERMANENT_FAILURE"

  # Spec default FAILED matches no code literal; ResolutionStatus is RESOLVED/NEEDS_IMAGE/RETRYABLE_FAILURE/TERMINAL_FAILURE/UNSUPPORTED.
  resolver_failure_status = "TERMINAL_FAILURE"
}
