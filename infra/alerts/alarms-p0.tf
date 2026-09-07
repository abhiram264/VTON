# Terraform code only — apply prohibited for slice-68.

# S3 request metric configuration to enable 5xxErrors CloudWatch metrics for the media bucket.
resource "aws_s3_bucket_metric" "aiv_alerts" {
  bucket = var.media_bucket_name
  name   = "aiv-alerts"
}

# 1. API down (unhealthy targets) — ≥1 unhealthy target for 2 out of 3 periods
resource "aws_cloudwatch_metric_alarm" "api_unhealthy_hosts" {
  alarm_name          = "${local.alarm_prefix}-p0-api-unhealthy-hosts"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 3
  datapoints_to_alarm = 2
  threshold           = 1
  alarm_description   = "Plan §68 P0: API down — ALB UnHealthyHostCount >= 1 for 2 of 3 60s periods."
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.p0.arn]
  ok_actions          = [aws_sns_topic.p0.arn]

  metric_name = "UnHealthyHostCount"
  namespace   = "AWS/ApplicationELB"
  period      = 60
  statistic   = "Average"

  dimensions = {
    LoadBalancer = var.alb_arn_suffix
    TargetGroup  = var.alb_target_group_arn_suffix
  }

  tags = local.tags
}

# 2. API down (no tasks) — live task count < 1 for 3 consecutive periods
resource "aws_cloudwatch_metric_alarm" "api_live_tasks" {
  alarm_name          = "${local.alarm_prefix}-p0-api-live-tasks"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  threshold           = 1
  alarm_description   = "Plan §68 P0: API down — ECS LiveTaskCount < 1 for 3 consecutive 60s periods."
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.p0.arn]
  ok_actions          = [aws_sns_topic.p0.arn]

  metric_name = "LiveTaskCount"
  namespace   = "AWS/ECS"
  period      = 60
  statistic   = "Minimum"

  dimensions = {
    ClusterName = var.ecs_cluster_name
    ServiceName = var.ecs_api_service_name
  }

  tags = local.tags
}

# 3. API down (error storm) — ALB 5XX count ≥ threshold for 2 out of 3 periods
resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name          = "${local.alarm_prefix}-p0-alb-5xx"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 3
  datapoints_to_alarm = 2
  threshold           = var.p0_alb_5xx_count
  alarm_description   = "Plan §68 P0: API down — ALB HTTPCode_ELB_5XX_Count >= threshold for 2 of 3 300s periods."
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.p0.arn]
  ok_actions          = [aws_sns_topic.p0.arn]

  metric_name = "HTTPCode_ELB_5XX_Count"
  namespace   = "AWS/ApplicationELB"
  period      = 300
  statistic   = "Sum"

  dimensions = {
    LoadBalancer = var.alb_arn_suffix
  }

  tags = local.tags
}

# 4. Database down — RDS connections < 1 for 5 consecutive periods
resource "aws_cloudwatch_metric_alarm" "rds_connections_zero" {
  alarm_name          = "${local.alarm_prefix}-p0-rds-connections-zero"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 5
  datapoints_to_alarm = 5
  threshold           = 1
  alarm_description   = "Plan §68 P0: database down — RDS DatabaseConnections < 1 for 5 consecutive 60s periods."
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.p0.arn]
  ok_actions          = [aws_sns_topic.p0.arn]

  metric_name = "DatabaseConnections"
  namespace   = "AWS/RDS"
  period      = 60
  statistic   = "Average"

  dimensions = {
    DBInstanceIdentifier = var.rds_db_instance_identifier
  }

  tags = local.tags
}

# 5. Authentication broken — auth failure ratio ≥ threshold for 2 out of 3 periods
# Note: When there is zero auth traffic (m1 + m2 = 0), expression evaluates to 0 (notBreaching) instead of divide-by-zero no-data.
resource "aws_cloudwatch_metric_alarm" "auth_failure_ratio" {
  alarm_name          = "${local.alarm_prefix}-p0-auth-failure-ratio"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 3
  datapoints_to_alarm = 2
  threshold           = var.p0_auth_failure_ratio
  alarm_description   = "Plan §68 P0: authentication broken — auth failure ratio >= threshold for 2 of 3 300s periods."
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.p0.arn]
  ok_actions          = [aws_sns_topic.p0.arn]

  metric_query {
    id          = "e1"
    expression  = "IF(m1+m2>0, m1/(m1+m2), 0)"
    label       = "Auth Failure Ratio"
    return_data = true
  }

  metric_query {
    id          = "m1"
    return_data = false

    metric {
      metric_name = "auth.failure.count"
      namespace   = var.metric_namespace
      period      = 300
      stat        = "Sum"
    }
  }

  metric_query {
    id          = "m2"
    return_data = false

    metric {
      metric_name = "auth.success.count"
      namespace   = var.metric_namespace
      period      = 300
      stat        = "Sum"
    }
  }

  tags = local.tags
}

# 6. All generations failing — generation failure ratio ≥ threshold for 2 out of 3 periods
# Note: When there is zero generation traffic, expression evaluates to 0 (notBreaching) instead of divide-by-zero no-data.
resource "aws_cloudwatch_metric_alarm" "generation_failure_ratio" {
  alarm_name          = "${local.alarm_prefix}-p0-generation-failure-ratio"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 3
  datapoints_to_alarm = 2
  threshold           = var.p0_generation_failure_ratio
  alarm_description   = "Plan §68 P0: all generations failing — generation failure ratio >= threshold for 2 of 3 300s periods."
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.p0.arn]
  ok_actions          = [aws_sns_topic.p0.arn]

  metric_query {
    id          = "e1"
    expression  = "IF(f1+f2+f3+f4+s1>0, (f1+f2+f3+f4)/(f1+f2+f3+f4+s1), 0)"
    label       = "Generation Failure Ratio"
    return_data = true
  }

  metric_query {
    id          = "f1"
    return_data = false

    metric {
      metric_name = "generation.terminal.count"
      namespace   = var.metric_namespace
      period      = 300
      stat        = "Sum"

      dimensions = {
        category = "FAILED"
      }
    }
  }

  metric_query {
    id          = "f2"
    return_data = false

    metric {
      metric_name = "generation.terminal.count"
      namespace   = var.metric_namespace
      period      = 300
      stat        = "Sum"

      dimensions = {
        category = "BUDGET_EXHAUSTED"
      }
    }
  }

  metric_query {
    id          = "f3"
    return_data = false

    metric {
      metric_name = "generation.terminal.count"
      namespace   = var.metric_namespace
      period      = 300
      stat        = "Sum"

      dimensions = {
        category = "DEADLINE_EXPIRED"
      }
    }
  }

  metric_query {
    id          = "f4"
    return_data = false

    metric {
      metric_name = "generation.terminal.count"
      namespace   = var.metric_namespace
      period      = 300
      stat        = "Sum"

      dimensions = {
        category = "UNKNOWN"
      }
    }
  }

  metric_query {
    id          = "s1"
    return_data = false

    metric {
      metric_name = "generation.terminal.count"
      namespace   = var.metric_namespace
      period      = 300
      stat        = "Sum"

      dimensions = {
        category = "COMPLETED"
      }
    }
  }

  tags = local.tags
}

# 7. S3 unavailable — S3 5xx errors ≥ 1 for 2 consecutive periods
resource "aws_cloudwatch_metric_alarm" "s3_5xx" {
  alarm_name          = "${local.alarm_prefix}-p0-s3-5xx"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  threshold           = 1
  alarm_description   = "Plan §68 P0: S3 unavailable — S3 5xxErrors >= 1 for 2 consecutive 60s periods."
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.p0.arn]
  ok_actions          = [aws_sns_topic.p0.arn]

  metric_name = "5xxErrors"
  namespace   = "AWS/S3"
  period      = 60
  statistic   = "Sum"

  dimensions = {
    BucketName = var.media_bucket_name
    FilterId   = aws_s3_bucket_metric.aiv_alerts.name
  }

  tags = local.tags
}
