# Terraform code only — apply prohibited for slice-68.

# Plan §68 P1 Condition 1: Provider failures > threshold
#
# SEARCH, not bare metric: GenerationMetrics.recordProviderError publishes
# provider.error with tags {provider, error_code} (GenerationMetrics.java:460-463),
# and a CloudWatch query matches the exact dimension set — a no-dimension query
# matches only a zero-dimension series, which is never published. SEARCH
# ({ns} provider.error, Sum) aggregates every provider/error_code series per
# period, which is what the threshold means. Statistic semantics unchanged (Sum).
resource "aws_cloudwatch_metric_alarm" "provider_errors" {
  alarm_name          = "${local.alarm_prefix}-p1-provider-errors"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 3
  datapoints_to_alarm = 2
  threshold           = var.p1_provider_error_count
  alarm_description   = "Plan §68 P1: provider failures > threshold — external provider error count exceeds threshold over 2 of 3 periods."
  alarm_actions       = [aws_sns_topic.p1.arn]
  treat_missing_data  = "notBreaching"
  tags                = local.tags

  metric_query {
    id          = "provider_errors"
    expression  = "SEARCH('{${var.metric_namespace}} provider.error', 'Sum', 300)"
    label       = "Provider Errors"
    return_data = true
  }
}

# Plan §68 P1 Condition 2: Queue delay too high (app queue age)
resource "aws_cloudwatch_metric_alarm" "queue_oldest_age" {
  alarm_name          = "${local.alarm_prefix}-p1-queue-oldest-age"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 3
  datapoints_to_alarm = 2
  metric_name         = "queue.oldest_age"
  namespace           = var.metric_namespace
  period              = 300
  statistic           = "Maximum"
  threshold           = var.p1_queue_oldest_age_seconds
  alarm_description   = "Plan §68 P1: queue delay too high — oldest queued generation item age in seconds exceeds threshold over 2 of 3 periods."
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
#
# SEARCH, not metric{} blocks: GenerationMetrics.recordQuality publishes
# generation.quality with the FULL tag set {policy_version, decision, reason_code,
# category} (GenerationMetrics.java:168-173); policy_version alone spans
# {baseline-v1, quality-v2} and is open-ended, so enumerating dimension sets in
# terraform is infeasible and a per-policy partition would corrupt the ratio. SEARCH
# ('decision', '<value>') aggregates every matching series across the other three
# dimensions, so the reject ratio is computed over the WHOLE population the
# publisher emits — exactly what this alarm means.
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
    expression  = "IF(q1+q2+q3>0, (q1+q2)/(q1+q2+q3), 0)"
    label       = "Quality Reject Ratio"
    return_data = true
  }

  metric_query {
    id         = "q1"
    expression = "SEARCH('{${var.metric_namespace}} generation.quality', 'Sum', 300, { decision = \"RETRY\" })"
  }

  metric_query {
    id         = "q2"
    expression = "SEARCH('{${var.metric_namespace}} generation.quality', 'Sum', 300, { decision = \"FAIL\" })"
  }

  metric_query {
    id         = "q3"
    expression = "SEARCH('{${var.metric_namespace}} generation.quality', 'Sum', 300, { decision = \"PASS\" })"
  }
}

# Plan §68 P1 Condition 5: Push failure spike
#
# Converted to a metric_query SEARCH so the dimension set MATCHES the publisher.
# NotificationMetrics.recordSend publishes notification.send.outcome with tags
# {channel, outcome} (NotificationMetrics.java:44-46); the old top-level
# `dimensions` block queried exactly {channel: FCM, outcome: PERMANENT_FAILURE},
# which IS the full published set — the queried series exists. But as a bare
# metric alarm with top-level dimensions, semantics stay identical when expressed
# as a SEARCH filtered on both tags; value domains verified:
# CHANNELS = {FCM} (NotificationMetrics.java:16),
# OUTCOMES contains PERMANENT_FAILURE (NotificationMetrics.java:20).
resource "aws_cloudwatch_metric_alarm" "push_failures" {
  alarm_name          = "${local.alarm_prefix}-p1-push-failures"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 3
  datapoints_to_alarm = 2
  threshold           = var.p1_push_failure_count
  alarm_description   = "Plan §68 P1: push failure spike — push notification failure count exceeds threshold over 2 of 3 periods."
  alarm_actions       = [aws_sns_topic.p1.arn]
  treat_missing_data  = "notBreaching"
  tags                = local.tags

  metric_query {
    id          = "push_failures"
    expression  = "SEARCH('{${var.metric_namespace}} notification.send.outcome', 'Sum', 300, { channel = \"FCM\", outcome = \"${var.notification_failed_outcome}\" })"
    label       = "Push Failures"
    return_data = true
  }
}

# Plan §68 P1 Condition 6: Generation cost spike
#
# SEARCH, not bare metric: GenerationMetrics.recordGenerationCostAmount publishes
# generation.cost.amount as a DistributionSummary with tags {provider, currency}
# (GenerationMetrics.java:482-485). A no-dimension query matches only a
# zero-dimension series, which is never published, and CloudWatch has no "Sum"
# statistic for a summary (it exports as a StatisticSet whose Sum is the SAMPLE
# COUNT). SEARCH with stat 'Sum' over the summary's tagged series aggregates the
# underlying per-datapoint sums CloudWatch stores for distribution metrics, which
# is the closest honest reading of "hourly spend". NOT changed blindly to
# Average/Maximum: choosing a different statistic would silently misreport cost
# and is a semantics change, not a reachability fix.
resource "aws_cloudwatch_metric_alarm" "generation_cost_hourly" {
  alarm_name          = "${local.alarm_prefix}-p1-generation-cost-hourly"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  threshold           = var.p1_hourly_cost_usd
  alarm_description   = "Plan §68 P1: generation cost spike — hourly generation cost spend exceeds budget threshold over 1 hour."
  alarm_actions       = [aws_sns_topic.p1.arn]
  treat_missing_data  = "notBreaching"
  tags                = local.tags

  metric_query {
    id          = "generation_cost_hourly"
    expression  = "SEARCH('{${var.metric_namespace}} generation.cost.amount', 'Sum', 3600)"
    label       = "Generation Cost Hourly"
    return_data = true
  }
}

# Plan §68 P1 Condition 7: Provider credits low
#
# SEARCH, not bare metric: updateProviderCreditBalance registers
# provider.credit_balance as a Gauge with tag {provider} (GenerationMetrics.java:524-526).
# A no-dimension query matches only a zero-dimension series, which is never
# published. SEARCH with stat 'Minimum' aggregates each provider series per period
# and the expression evaluator takes the minimum across them — the honest reading
# of "any provider at or below the floor". Statistic semantics unchanged (Minimum).
resource "aws_cloudwatch_metric_alarm" "provider_credits_low" {
  alarm_name          = "${local.alarm_prefix}-p1-provider-credits-low"
  comparison_operator = "LessThanOrEqualToThreshold"
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  threshold           = var.p1_provider_credit_low
  alarm_description   = "Plan §68 P1: provider credits low — provider credit balance gauge at or below floor threshold."
  alarm_actions       = [aws_sns_topic.p1.arn]
  treat_missing_data  = "notBreaching"
  tags                = local.tags

  metric_query {
    id          = "provider_credits_low"
    expression  = "SEARCH('{${var.metric_namespace}} provider.credit_balance', 'Minimum', 300)"
    label       = "Provider Credit Balance"
    return_data = true
  }
}
