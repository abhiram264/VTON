# Terraform code only — apply prohibited for slice-68.

# Plan §68 P1 Condition 1: Provider failures > threshold
resource "aws_cloudwatch_metric_alarm" "provider_errors" {
  alarm_name          = "${local.alarm_prefix}-p1-provider-errors"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 3
  datapoints_to_alarm = 2
  metric_name         = "provider.error.count"
  namespace           = var.metric_namespace
  period              = 300
  statistic           = "Sum"
  threshold           = var.p1_provider_error_count
  alarm_description   = "Plan §68 P1: provider failures > threshold — external provider error count exceeds threshold over 2 of 3 periods."
  alarm_actions       = [aws_sns_topic.p1.arn]
  treat_missing_data  = "notBreaching"
  tags                = local.tags
}

# Plan §68 P1 Condition 2: Queue delay too high (app queue age)
resource "aws_cloudwatch_metric_alarm" "queue_oldest_age" {
  alarm_name          = "${local.alarm_prefix}-p1-queue-oldest-age"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 3
  datapoints_to_alarm = 2
  metric_name         = "queue.oldest_age.value"
  namespace           = var.metric_namespace
  period              = 300
  statistic           = "Maximum"
  threshold           = var.p1_queue_oldest_age_ms
  alarm_description   = "Plan §68 P1: queue delay too high — oldest queued generation item age exceeds threshold over 2 of 3 periods."
  alarm_actions       = [aws_sns_topic.p1.arn]
  treat_missing_data  = "notBreaching"
  tags                = local.tags
}

# Plan §68 P1 Condition 3: Queue delay too high (infrastructure SQS backlog)
resource "aws_cloudwatch_metric_alarm" "sqs_backlog" {
  alarm_name          = "${local.alarm_prefix}-p1-sqs-backlog"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 300
  statistic           = "Average"
  threshold           = var.p1_sqs_backlog_depth
  alarm_description   = "Plan §68 P1: queue delay too high — SQS generation queue visible messages backlog exceeds threshold over 3 of 3 periods."
  alarm_actions       = [aws_sns_topic.p1.arn]
  treat_missing_data  = "notBreaching"
  tags                = local.tags

  dimensions = {
    QueueName = var.generation_queue_name
  }
}

# Plan §68 P1 Condition 4: Quality failures spike
resource "aws_cloudwatch_metric_alarm" "quality_reject_ratio" {
  alarm_name          = "${local.alarm_prefix}-p1-quality-reject-ratio"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 6
  datapoints_to_alarm = 6
  threshold           = var.p1_quality_reject_ratio
  alarm_description   = "Plan §68 P1: quality failures spike — overall generation quality reject ratio exceeds threshold over 6 consecutive periods."
  alarm_actions       = [aws_sns_topic.p1.arn]
  treat_missing_data  = "notBreaching"
  tags                = local.tags

  metric_query {
    id          = "e1"
    expression  = "m1/m2"
    label       = "Quality Reject Ratio"
    return_data = true
  }

  metric_query {
    id          = "m1"
    return_data = false

    metric {
      metric_name = "generation.quality_reject.count"
      namespace   = var.metric_namespace
      period      = 300
      stat        = "Sum"
    }
  }

  metric_query {
    id          = "m2"
    return_data = false

    metric {
      metric_name = "generation.count.count"
      namespace   = var.metric_namespace
      period      = 300
      stat        = "Sum"
    }
  }
}

# Plan §68 P1 Condition 5: Push failure spike
resource "aws_cloudwatch_metric_alarm" "push_failures" {
  alarm_name          = "${local.alarm_prefix}-p1-push-failures"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 3
  datapoints_to_alarm = 2
  metric_name         = "notification.send.outcome.count"
  namespace           = var.metric_namespace
  period              = 300
  statistic           = "Sum"
  threshold           = var.p1_push_failure_count
  alarm_description   = "Plan §68 P1: push failure spike — push notification failure count exceeds threshold over 2 of 3 periods."
  alarm_actions       = [aws_sns_topic.p1.arn]
  treat_missing_data  = "notBreaching"
  tags                = local.tags

  dimensions = {
    outcome = var.notification_failed_outcome
  }
}

# Plan §68 P1 Condition 6: Generation cost spike
resource "aws_cloudwatch_metric_alarm" "generation_cost_hourly" {
  alarm_name          = "${local.alarm_prefix}-p1-generation-cost-hourly"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  metric_name         = "generation.cost.attempt.sum"
  namespace           = var.metric_namespace
  period              = 3600
  statistic           = "Sum"
  threshold           = var.p1_hourly_cost_usd
  alarm_description   = "Plan §68 P1: generation cost spike — hourly generation cost spend exceeds budget threshold over 1 hour."
  alarm_actions       = [aws_sns_topic.p1.arn]
  treat_missing_data  = "notBreaching"
  tags                = local.tags
}

# Plan §68 P1 Condition 7: Provider credits low
resource "aws_cloudwatch_metric_alarm" "provider_credits_low" {
  alarm_name          = "${local.alarm_prefix}-p1-provider-credits-low"
  comparison_operator = "LessThanOrEqualToThreshold"
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  metric_name         = "provider.credit_balance.value"
  namespace           = var.metric_namespace
  period              = 300
  statistic           = "Minimum"
  threshold           = var.p1_provider_credit_low
  alarm_description   = "Plan §68 P1: provider credits low — provider credit balance gauge at or below floor threshold."
  alarm_actions       = [aws_sns_topic.p1.arn]
  treat_missing_data  = "notBreaching"
  tags                = local.tags
}
