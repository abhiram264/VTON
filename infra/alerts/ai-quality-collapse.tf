# Terraform code only — apply prohibited for slice-69.

# Plan §69: AI-Specific Production Alert — Provider HTTP success = normal BUT Quality Gate pass rate collapses.
resource "aws_cloudwatch_metric_alarm" "ai_quality_collapse" {
  alarm_name          = "${var.ai_quality_collapse_alarm_name_prefix}ai-quality-collapse"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = var.ai_quality_collapse_evaluation_periods
  datapoints_to_alarm = var.ai_quality_collapse_datapoints_to_alarm
  threshold           = 1
  alarm_description   = "Plan §69: Provider HTTP success = normal (provider_success_ratio >= ${var.ai_quality_collapse_provider_success_min}) BUT Quality Gate pass rate collapses (reject_share > ${1 - var.ai_quality_collapse_pass_rate_floor}) with volume >= ${var.ai_quality_collapse_min_evaluated}."
  alarm_actions       = [aws_sns_topic.p1.arn]
  treat_missing_data  = "notBreaching"
  tags                = local.tags

  metric_query {
    id          = "breach"
    expression  = "IF(AND(IF(succ + fail1 + fail2 + fail3 + fail4 > 0, succ / (succ + fail1 + fail2 + fail3 + fail4), 1) >= ${var.ai_quality_collapse_provider_success_min}, IF(succ > 0, (rej1 + rej2) / succ, 0) > ${1 - var.ai_quality_collapse_pass_rate_floor}, succ >= ${var.ai_quality_collapse_min_evaluated}), 1, 0)"
    label       = "AI Quality Collapse Breach"
    return_data = true
  }

  metric_query {
    id          = "succ"
    return_data = false

    metric {
      metric_name = "generation.terminal.count"
      namespace   = var.metric_namespace
      period      = var.ai_quality_collapse_period_seconds
      stat        = "Sum"

      dimensions = {
        category = "COMPLETED"
      }
    }
  }

  metric_query {
    id          = "fail1"
    return_data = false

    metric {
      metric_name = "generation.terminal.count"
      namespace   = var.metric_namespace
      period      = var.ai_quality_collapse_period_seconds
      stat        = "Sum"

      dimensions = {
        category = "FAILED"
      }
    }
  }

  metric_query {
    id          = "fail2"
    return_data = false

    metric {
      metric_name = "generation.terminal.count"
      namespace   = var.metric_namespace
      period      = var.ai_quality_collapse_period_seconds
      stat        = "Sum"

      dimensions = {
        category = "BUDGET_EXHAUSTED"
      }
    }
  }

  metric_query {
    id          = "fail3"
    return_data = false

    metric {
      metric_name = "generation.terminal.count"
      namespace   = var.metric_namespace
      period      = var.ai_quality_collapse_period_seconds
      stat        = "Sum"

      dimensions = {
        category = "DEADLINE_EXPIRED"
      }
    }
  }

  metric_query {
    id          = "fail4"
    return_data = false

    metric {
      metric_name = "generation.terminal.count"
      namespace   = var.metric_namespace
      period      = var.ai_quality_collapse_period_seconds
      stat        = "Sum"

      dimensions = {
        category = "UNKNOWN"
      }
    }
  }

  metric_query {
    id          = "rej1"
    return_data = false

    metric {
      metric_name = "generation.quality.count"
      namespace   = var.metric_namespace
      period      = var.ai_quality_collapse_period_seconds
      stat        = "Sum"

      dimensions = {
        decision = "RETRY"
      }
    }
  }

  metric_query {
    id          = "rej2"
    return_data = false

    metric {
      metric_name = "generation.quality.count"
      namespace   = var.metric_namespace
      period      = var.ai_quality_collapse_period_seconds
      stat        = "Sum"

      dimensions = {
        decision = "FAIL"
      }
    }
  }
}
