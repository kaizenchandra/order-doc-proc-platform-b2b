variable "project_id" {
  type = string
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$", var.project_id))
    error_message = "Use an existing GCP project ID."
  }
}
variable "region" {
  type    = string
  default = "asia-south1"
}
variable "environment" {
  type = string
  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "Environment must be dev, staging, or prod."
  }
}
variable "enable_cloud_run" {
  description = "Enable only after images, SQL users/schema, and secret versions exist."
  type        = bool
  default     = false
}
variable "images" {
  description = "Artifact Registry digest references, keyed by document and notification."
  type        = map(string)
  default     = {}
  validation {
    condition = !var.enable_cloud_run || alltrue([
      for service in ["document", "notification"] : can(regex("^[a-z0-9-]+-docker\\.pkg\\.dev/[^ ]+@sha256:[a-f0-9]{64}$", lookup(var.images, service, "")))
    ])
    error_message = "Cloud Run requires document and notification Artifact Registry image digests."
  }
}
variable "notification_password_version" {
  description = "Existing numeric Secret Manager version; no plaintext password enters Terraform."
  type        = string
  default     = ""
  validation {
    condition     = !var.enable_cloud_run || can(regex("^[1-9][0-9]*$", var.notification_password_version))
    error_message = "Cloud Run requires a pinned numeric notification password secret version."
  }
}
variable "database_prepared" {
  description = "Operator attestation that isolated SQL users/grants and both Flyway schemas are prepared."
  type        = bool
  default     = false
  validation {
    condition     = !var.enable_cloud_run || var.database_prepared
    error_message = "Prepare the SQL users and schemas before enabling Cloud Run."
  }
}
variable "api_hostname" {
  description = "Optional hostname for a managed certificate and global address; empty disables certificate."
  type        = string
  default     = ""
  validation {
    condition     = var.api_hostname == "" || can(regex("^[a-z0-9][a-z0-9.-]+\\.[a-z]{2,}$", var.api_hostname))
    error_message = "Use a DNS hostname without a scheme or path."
  }
}
variable "dns_managed_zone" {
  description = "Optional existing Cloud DNS zone in this project for the API A record."
  type        = string
  default     = ""
  validation {
    condition     = var.dns_managed_zone == "" || var.api_hostname != ""
    error_message = "A DNS zone requires an API hostname."
  }
}

variable "notification_channels" {
  description = "Existing verified Cloud Monitoring channel resource names; empty creates console incidents only."
  type        = list(string)
  default     = []
  validation {
    condition     = alltrue([for channel in var.notification_channels : can(regex("^projects/[^/]+/notificationChannels/[^/]+$", channel))])
    error_message = "Use full Cloud Monitoring notification channel resource names."
  }
}
