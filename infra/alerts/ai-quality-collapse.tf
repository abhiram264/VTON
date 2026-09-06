# Terraform code only — apply prohibited for slice-69.

# Plan §69: AI-Specific Production Alert — Provider HTTP success = normal BUT Quality Gate pass rate collapses.
resource "aws_cloudwatch_metric_alarm" "ai_quality_collapse" {
  alarm_name          = "${var.ai_quality_collapse_alarm_name_prefix}ai-quality-collapse"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = var.ai_quality_collapse_evaluation_periods
  datapoints_to_alarm = var.ai_quality_collapse_datapoints_to_alarm
  threshold           = 1
  alarm_description   = "Plan §69: Provider HTTP success = normal (provider_success_ratio >= ${var.ai_quality_collapse_provider_success_min}) BUT Quality Gate pass rate collapses (reject_share > ${1 - var.ai_quality_collapse_pass_rate_floor}) with volume >= ${var.ai_quality_collapse_min_evaluated}."
  alarm_actions       = var.ai_quality_collapse_alarm_topic_arn != null ? [var.ai_quality_collapse_alarm_topic_arn] : []
  treat_missing_data  = "notBreaching"
  tags                = local.tags

  metric_query {
    id          = "breach"
    expression  = "IF(AND(IF(succ + fail > 0, succ / (succ + fail), 1) >= ${var.ai_quality_collapse_provider_success_min}, IF(succ > 0, rej / succ, 0) > ${1 - var.ai_quality_collapse_pass_rate_floor}, succ >= ${var.ai_quality_collapse_min_evaluated}), 1, 0)"
    label       = "AI Quality Collapse Breach"
    return_data = true
  }

  metric_query {
    id          = "succ"
    return_data = false

    metric {
      metric_name = var.ai_quality_collapse_metric_success
      namespace   = var.ai_quality_collapse_metric_namespace
      period      = var.ai_quality_collapse_period_seconds
      stat        = "Sum"
    }
  }

  metric_query {
    id          = "fail"
    return_data = false

    metric {
      metric_name = var.ai_quality_collapse_metric_failure
      namespace   = var.ai_quality_collapse_metric_namespace
      period      = var.ai_quality_collapse_period_seconds
      stat        = "Sum"
    }
  }

  metric_query {
    id          = "rej"
    return_data = false

    metric {
      metric_name = var.ai_quality_collapse_metric_quality_reject
      namespace   = var.ai_quality_collapse_metric_namespace
      period      = var.ai_quality_collapse_period_seconds
      stat        = "Sum"
    }
  }
}
