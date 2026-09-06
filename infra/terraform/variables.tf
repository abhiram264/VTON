# Terraform code only — apply prohibited for slice-55.

variable "environment" {
  description = "Deployment environment name interpolated into every resource name."
  type        = string
  default     = "production"
}

variable "aws_region" {
  description = "AWS region in which all resources are provisioned."
  type        = string
  default     = "us-east-1"
}

variable "vpc_cidr" {
  description = "CIDR block of the dedicated production VPC."
  type        = string
  default     = "10.42.0.0/16"
}

variable "availability_zones" {
  description = "Availability zones used for subnet and RDS placement."
  type        = list(string)
  default     = ["us-east-1a", "us-east-1b"]
}

variable "backend_container_image" {
  description = "ECR image reference for the backend container, supplied at apply time."
  type        = string
}

variable "firebase_project_id" {
  description = "Firebase project ID the backend uses for authentication."
  type        = string
}

variable "acm_certificate_arn" {
  description = "ACM certificate ARN; the HTTPS 443 listener is created only when this is set."
  type        = string
  default     = ""
}

variable "api_desired_count" {
  description = "Desired number of API Fargate tasks."
  type        = number
  default     = 1
}

variable "worker_desired_count" {
  description = "Desired number of generation worker Fargate tasks."
  type        = number
  default     = 1
}

variable "api_cpu" {
  description = "CPU units reserved for the API task definition."
  type        = number
  default     = 512
}

variable "api_memory" {
  description = "Memory in MiB reserved for the API task definition."
  type        = number
  default     = 1024
}

variable "worker_cpu" {
  description = "CPU units reserved for the worker task definition."
  type        = number
  default     = 1024
}

variable "worker_memory" {
  description = "Memory in MiB reserved for the worker task definition."
  type        = number
  default     = 2048
}

variable "db_instance_class" {
  description = "RDS instance class of the PostgreSQL database."
  type        = string
  default     = "db.t4g.small"
}

variable "db_allocated_storage_gb" {
  description = "Allocated storage in GiB for the PostgreSQL database."
  type        = number
  default     = 20
}

variable "db_name" {
  description = "Name of the initial database created in the PostgreSQL instance."
  type        = string
  default     = "vault"
}

variable "db_master_username" {
  description = "Master username of the PostgreSQL instance."
  type        = string
  default     = "vault_app"
}

variable "db_backup_retention_days" {
  description = "Automated backup retention period in days for the PostgreSQL database."
  type        = number
  default     = 7
}

variable "log_retention_days" {
  description = "CloudWatch log retention in days for the service log groups."
  type        = number
  default     = 30
}

variable "tags" {
  description = "Base tags applied to every resource; Environment is merged in local.tags."
  type        = map(string)
  default     = { Project = "ai-fashion-vault", ManagedBy = "terraform" }
}
