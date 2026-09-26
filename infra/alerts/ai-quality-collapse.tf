# Terraform code only — apply prohibited for slice-69.

# Plan §69: Technical generation success = normal BUT Quality Gate pass rate collapses.
resource "aws_cloudwatch_metric_alarm" "ai_quality_collapse" {
  alarm_name          = "${var.ai_quality_collapse_alarm_name_prefix}ai-quality-collapse"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = var.ai_quality_collapse_evaluation_periods
  datapoints_to_alarm = var.ai_quality_collapse_datapoints_to_alarm
  threshold           = 1
  alarm_description   = "Plan §69: Technical generation success ratio >= ${var.ai_quality_collapse_provider_success_min} BUT Quality Gate reject share > ${1 - var.ai_quality_collapse_pass_rate_floor} with evaluated decisions >= ${var.ai_quality_collapse_min_evaluated}."
  alarm_actions       = [aws_sns_topic.p1.arn]
  treat_missing_data  = "notBreaching"
  tags                = local.tags

  metric_query {
    id          = "breach"
    expression  = "IF(AND(IF(tech_success + tech_failure > 0, tech_success / (tech_success + tech_failure), 1) >= ${var.ai_quality_collapse_provider_success_min}, IF(retry + fail + pass > 0, (retry + fail) / (retry + fail + pass), 0) > ${1 - var.ai_quality_collapse_pass_rate_floor}, retry + fail + pass >= ${var.ai_quality_collapse_min_evaluated}), 1, 0)"
    label       = "AI Quality Collapse Breach"
    return_data = true
  }

  metric_query {
    id          = "tech_success"
    return_data = false

    metric {
      metric_name = "generation.cost.attempt"
      namespace   = var.metric_namespace
      period      = var.ai_quality_collapse_period_seconds
      stat        = "Sum"

      dimensions = {
        technical_outcome = "SUCCESS"
      }
    }
  }

  metric_query {
    id          = "tech_failure"
    return_data = false

    metric {
      metric_name = "generation.cost.attempt"
      namespace   = var.metric_namespace
      period      = var.ai_quality_collapse_period_seconds
      stat        = "Sum"

      dimensions = {
        technical_outcome = "FAILED"
      }
    }
  }

  metric_query {
    id          = "retry"
    return_data = false

    metric {
      metric_name = "generation.quality"
      namespace   = var.metric_namespace
      period      = var.ai_quality_collapse_period_seconds
      stat        = "Sum"

      dimensions = {
        decision = "RETRY"
      }
    }
  }

  metric_query {
    id          = "fail"
    return_data = false

    metric {
      metric_name = "generation.quality"
      namespace   = var.metric_namespace
      period      = var.ai_quality_collapse_period_seconds
      stat        = "Sum"

      dimensions = {
        decision = "FAIL"
      }
    }
  }

  metric_query {
    id          = "pass"
    return_data = false

    metric {
      metric_name = "generation.quality"
      namespace   = var.metric_namespace
      period      = var.ai_quality_collapse_period_seconds
      stat        = "Sum"

      dimensions = {
        decision = "PASS"
      }
    }
  }
}
