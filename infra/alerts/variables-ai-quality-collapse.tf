# Terraform code only — apply prohibited for slice-69.

variable "ai_quality_collapse_provider_success_min" {
  description = "Plan §65 technical generation success ratio floor (provider normal bar)."
  type        = number
  default     = 0.95
}

variable "ai_quality_collapse_pass_rate_floor" {
  description = "Plan §65 quality pass rate floor (reject share ceiling is 1 - floor)."
  type        = number
  default     = 0.75
}

variable "ai_quality_collapse_min_evaluated" {
  description = "Minimum evaluated generations per period to prevent low-volume noise."
  type        = number
  default     = 10
}

variable "ai_quality_collapse_period_seconds" {
  description = "Evaluation period in seconds for ai-quality-collapse metric math."
  type        = number
  default     = 300
}

variable "ai_quality_collapse_evaluation_periods" {
  description = "Number of periods over which data is compared to the threshold."
  type        = number
  default     = 6
}

variable "ai_quality_collapse_datapoints_to_alarm" {
  description = "Number of datapoints that must be breaching to trigger the alarm."
  type        = number
  default     = 5
}

variable "ai_quality_collapse_alarm_name_prefix" {
  description = "Prefix prepended to the ai-quality-collapse CloudWatch alarm name."
  type        = string
  default     = ""
}
