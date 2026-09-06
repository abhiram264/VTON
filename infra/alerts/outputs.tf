# Terraform code only — apply prohibited for slice-68.

output "alerts_p0_topic_arn" {
  description = "ARN of the SNS topic receiving P0 critical alerts."
  value       = aws_sns_topic.p0.arn
}

output "alerts_p1_topic_arn" {
  description = "ARN of the SNS topic receiving P1 warning alerts."
  value       = aws_sns_topic.p1.arn
}

output "alerts_p2_topic_arn" {
  description = "ARN of the SNS topic receiving P2 informational alerts."
  value       = aws_sns_topic.p2.arn
}

output "p0_alarm_names" {
  description = "List of all P0 CloudWatch metric alarm names in order."
  value = [
    aws_cloudwatch_metric_alarm.api_unhealthy_hosts.alarm_name,
    aws_cloudwatch_metric_alarm.api_live_tasks.alarm_name,
    aws_cloudwatch_metric_alarm.alb_5xx.alarm_name,
    aws_cloudwatch_metric_alarm.rds_connections_zero.alarm_name,
    aws_cloudwatch_metric_alarm.auth_failure_ratio.alarm_name,
    aws_cloudwatch_metric_alarm.generation_failure_ratio.alarm_name,
    aws_cloudwatch_metric_alarm.s3_5xx.alarm_name,
  ]
}

output "p1_alarm_names" {
  description = "List of all P1 CloudWatch metric alarm names in order."
  value = [
    aws_cloudwatch_metric_alarm.provider_errors.alarm_name,
    aws_cloudwatch_metric_alarm.queue_oldest_age.alarm_name,
    aws_cloudwatch_metric_alarm.sqs_backlog.alarm_name,
    aws_cloudwatch_metric_alarm.quality_reject_ratio.alarm_name,
    aws_cloudwatch_metric_alarm.push_failures.alarm_name,
    aws_cloudwatch_metric_alarm.generation_cost_hourly.alarm_name,
    aws_cloudwatch_metric_alarm.provider_credits_low.alarm_name,
  ]
}

output "p2_alarm_names" {
  description = "List of all P2 CloudWatch metric alarm names across resolver and category families."
  value = concat(
    [for a in aws_cloudwatch_metric_alarm.import_resolver_degraded : a.alarm_name],
    [for a in aws_cloudwatch_metric_alarm.category_quality_degraded : a.alarm_name]
  )
}

output "metric_namespace" {
  description = "CloudWatch custom metric namespace used by the alerting module."
  value       = var.metric_namespace
}
