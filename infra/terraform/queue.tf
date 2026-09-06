# Terraform code only — apply prohibited for slice-55.

resource "aws_sqs_queue" "generation_work_dlq" {
  name                       = local.generation_dlq_queue_name
  visibility_timeout_seconds = 120
  receive_wait_time_seconds  = 20
  message_retention_seconds  = 1209600
  tags                       = local.tags
}

resource "aws_sqs_queue" "generation_work" {
  name                       = local.generation_queue_name
  visibility_timeout_seconds = 120
  receive_wait_time_seconds  = 20
  message_retention_seconds  = 345600
  redrive_policy             = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.generation_work_dlq.arn
    maxReceiveCount     = 5
  })
  tags                       = local.tags
}
