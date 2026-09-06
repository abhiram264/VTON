# Terraform code only — apply prohibited for slice-68.

# Plan §68 P2 Condition 1: Single retailer/source import degraded
resource "aws_cloudwatch_metric_alarm" "import_resolver_degraded" {
  for_each = toset(var.source_resolver_ids)

  alarm_name          = "${local.alarm_prefix}-p2-import-resolver-degraded-${each.key}"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 6
  datapoints_to_alarm = 3
  threshold           = var.p2_resolver_failure_ratio
  alarm_description   = "Plan §68 P2: source import degraded for resolver ${each.key} — failure ratio >= threshold over 3 of 6 hours."
  alarm_actions       = [aws_sns_topic.p2.arn]
  treat_missing_data  = "notBreaching"
  tags                = local.tags

  metric_query {
    id          = "e1"
    expression  = "m1/m2"
    label       = "Resolver ${each.key} Failure Ratio"
    return_data = true
  }

  metric_query {
    id          = "m1"
    return_data = false

    metric {
      metric_name = "share.import.resolver.outcome.count"
      namespace   = var.metric_namespace
      period      = 3600
      stat        = "Sum"

      dimensions = {
        resolver = each.key
        status   = var.resolver_failure_status
      }
    }
  }

  metric_query {
    id          = "m2"
    return_data = false

    metric {
      metric_name = "share.import.resolver.outcome.count"
      namespace   = var.metric_namespace
      period      = 3600
      stat        = "Sum"

      dimensions = {
        resolver = each.key
      }
    }
  }
}

# Plan §68 P2 Condition 2: Single garment category quality degraded
resource "aws_cloudwatch_metric_alarm" "category_quality_degraded" {
  for_each = toset(var.garment_categories)

  alarm_name          = "${local.alarm_prefix}-p2-category-quality-degraded-${each.key}"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 6
  datapoints_to_alarm = 3
  threshold           = var.p2_category_quality_reject_ratio
  alarm_description   = "Plan §68 P2: garment category quality degraded for ${each.key} — quality reject ratio >= threshold over 3 of 6 hours."
  alarm_actions       = [aws_sns_topic.p2.arn]
  treat_missing_data  = "notBreaching"
  tags                = local.tags

  metric_query {
    id          = "e1"
    expression  = "m1/m2"
    label       = "Category ${each.key} Quality Reject Ratio"
    return_data = true
  }

  metric_query {
    id          = "m1"
    return_data = false

    metric {
      metric_name = "generation.quality_reject.count"
      namespace   = var.metric_namespace
      period      = 3600
      stat        = "Sum"

      dimensions = {
        category = each.key
      }
    }
  }

  metric_query {
    id          = "m2"
    return_data = false

    metric {
      metric_name = "generation.count.count"
      namespace   = var.metric_namespace
      period      = 3600
      stat        = "Sum"
    }
  }
}
