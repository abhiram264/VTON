# Terraform code only — apply prohibited for slice-68.

resource "aws_sns_topic" "p0" {
  name = "${var.alert_name_prefix}-alerts-p0"
  tags = local.tags
}

resource "aws_sns_topic_subscription" "p0_email" {
  for_each               = toset(var.operator_emails_p0)
  topic_arn              = aws_sns_topic.p0.arn
  protocol               = "email"
  endpoint               = each.value
  endpoint_auto_confirms = false
}

resource "aws_sns_topic" "p1" {
  name = "${var.alert_name_prefix}-alerts-p1"
  tags = local.tags
}

resource "aws_sns_topic_subscription" "p1_email" {
  for_each               = toset(var.operator_emails_p1)
  topic_arn              = aws_sns_topic.p1.arn
  protocol               = "email"
  endpoint               = each.value
  endpoint_auto_confirms = false
}

resource "aws_sns_topic" "p2" {
  name = "${var.alert_name_prefix}-alerts-p2"
  tags = local.tags
}

resource "aws_sns_topic_subscription" "p2_email" {
  for_each               = toset(var.operator_emails_p2)
  topic_arn              = aws_sns_topic.p2.arn
  protocol               = "email"
  endpoint               = each.value
  endpoint_auto_confirms = false
}
