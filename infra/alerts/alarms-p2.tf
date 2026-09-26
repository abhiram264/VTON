# Terraform code only — apply prohibited for slice-68.

# Plan §68 P2 Condition 1: Single retailer/source import degraded
#
# SEARCH, not metric{} blocks: SourceResolverMetrics.record publishes
# share.import.resolver.outcome with tags {resolver, status, reason}
# (SourceResolverMetrics.java:86-89), so a metric{} block naming {resolver,
# status} matches no published series (reason is always present). SEARCH filters
# resolver AND status while aggregating over reason — the failure ratio over
# every reason the publisher emits. Status domain verified:
# ResolutionResult status names plus the literal "EXCEPTION"
# (SourceResolverMetrics.java:70,:76).
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
    expression  = "IF(m2>0, (m1+m3)/m2, 0)"
    label       = "Resolver ${each.key} Failure Ratio"
    return_data = true
  }

  metric_query {
    id         = "m1"
    expression = "SEARCH('{${var.metric_namespace}} share.import.resolver.outcome', 'Sum', 3600, { resolver = \"${each.key}\", status = \"${var.resolver_failure_status}\" })"
  }

  metric_query {
    id         = "m3"
    expression = "SEARCH('{${var.metric_namespace}} share.import.resolver.outcome', 'Sum', 3600, { resolver = \"${each.key}\", status = \"EXCEPTION\" })"
  }

  metric_query {
    id         = "m2"
    expression = "SEARCH('{${var.metric_namespace}} share.import.resolver.outcome', 'Sum', 3600, { resolver = \"${each.key}\" })"
  }
}

# Plan §68 P2 Condition 2: Single garment category quality degraded
#
# SEARCH, not metric{} blocks: generation.quality publishes the full tag set
# {policy_version, decision, reason_code, category} (GenerationMetrics.java:168-173),
# so a metric{} block naming {decision, category} can never match. SEARCH filters
# decision AND category while aggregating over policy_version and reason_code —
# the per-category reject ratio over the whole population the publisher emits,
# which is exactly what this alarm means. var.garment_categories values are
# validated canonical category values (GenerationMetrics.java:621-634).
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
    expression  = "IF(q1+q2+q3>0, (q1+q2)/(q1+q2+q3), 0)"
    label       = "Category ${each.key} Quality Reject Ratio"
    return_data = true
  }

  metric_query {
    id         = "q1"
    expression = "SEARCH('{${var.metric_namespace}} generation.quality', 'Sum', 3600, { decision = \"RETRY\", category = \"${each.key}\" })"
  }

  metric_query {
    id         = "q2"
    expression = "SEARCH('{${var.metric_namespace}} generation.quality', 'Sum', 3600, { decision = \"FAIL\", category = \"${each.key}\" })"
  }

  metric_query {
    id         = "q3"
    expression = "SEARCH('{${var.metric_namespace}} generation.quality', 'Sum', 3600, { decision = \"PASS\", category = \"${each.key}\" })"
  }
}
