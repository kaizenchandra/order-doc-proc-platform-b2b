terraform {
  required_version = ">= 1.11, < 2.0"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "8.1.0"
    }
  }
}
variable "project_id" { type = string }
variable "bucket_name" { type = string }
variable "region" {
  type    = string
  default = "asia-south1"
}
variable "state_admin_member" {
  description = "Existing CI/platform principal, e.g. serviceAccount:terraform@PROJECT.iam.gserviceaccount.com."
  type        = string
  validation {
    condition     = can(regex("^(serviceAccount:|group:)", var.state_admin_member))
    error_message = "Use an explicit service account or operator group, never a public principal."
  }
}
provider "google" { project = var.project_id }
resource "google_project_service" "storage" {
  project            = var.project_id
  service            = "storage.googleapis.com"
  disable_on_destroy = false
}
resource "google_storage_bucket" "state" {
  project                     = var.project_id
  name                        = var.bucket_name
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false
  versioning { enabled = true }
  # Saved application plans are short-lived; this never matches Terraform state prefixes.
  lifecycle_rule {
    condition {
      age            = 1
      matches_prefix = ["release-plans/"]
    }
    action { type = "Delete" }
  }
  lifecycle { prevent_destroy = true }
  depends_on = [google_project_service.storage]
}
resource "google_storage_bucket_iam_member" "state_admin" {
  bucket = google_storage_bucket.state.name
  role   = "roles/storage.objectAdmin"
  member = var.state_admin_member
}
output "bucket" { value = google_storage_bucket.state.name }
