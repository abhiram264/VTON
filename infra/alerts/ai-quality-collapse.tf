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

  # SEARCH, not metric{}: GenerationMetrics publishes these meters with their full
  # tag set (generation.cost.attempt = provider, model, technical_outcome, quality_outcome,
  # cost_status; generation.quality = policy_version, decision, reason_code, category), and a
  # CloudWatch metric query matches the dimension set exactly. A metric{} block naming only one
  # of those dimensions can never match, so the alarm would never fire.
  metric_query {
    id         = "tech_success"
    expression = "SEARCH('{${var.metric_namespace}} generation.cost.attempt', 'technical_outcome', 'SUCCESS', ${var.ai_quality_collapse_period_seconds})"
  }

  metric_query {
    id         = "tech_failure"
    expression = "SEARCH('{${var.metric_namespace}} generation.cost.attempt', 'technical_outcome', 'FAILED', ${var.ai_quality_collapse_period_seconds})"
  }

  metric_query {
    id         = "retry"
    expression = "SEARCH('{${var.metric_namespace}} generation.quality', 'decision', 'RETRY', ${var.ai_quality_collapse_period_seconds})"
  }

  metric_query {
    id         = "fail"
    expression = "SEARCH('{${var.metric_namespace}} generation.quality', 'decision', 'FAIL', ${var.ai_quality_collapse_period_seconds})"
  }

  metric_query {
    id         = "pass"
    expression = "SEARCH('{${var.metric_namespace}} generation.quality', 'decision', 'PASS', ${var.ai_quality_collapse_period_seconds})"
  }
}
