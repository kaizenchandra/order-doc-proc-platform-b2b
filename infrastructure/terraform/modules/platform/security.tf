# Restrict API permissions; signBlob still confers powerful signing capability on this identity.
resource "google_project_iam_custom_role" "blob_signer" {
  project     = var.project_id
  role_id     = "orderPlatformBlobSigner_${var.environment}"
  title       = "Order platform blob signer (${var.environment})"
  permissions = ["iam.serviceAccounts.signBlob"]
  depends_on  = [google_project_service.api]
}
resource "google_project_iam_custom_role" "object_read" {
  project     = var.project_id
  role_id     = "orderPlatformObjectRead_${var.environment}"
  title       = "Order platform exact object read (${var.environment})"
  permissions = ["storage.objects.get"]
  depends_on  = [google_project_service.api]
}
resource "google_project_iam_custom_role" "object_create" {
  project     = var.project_id
  role_id     = "orderPlatformObjectCreate_${var.environment}"
  title       = "Order platform object create (${var.environment})"
  permissions = ["storage.objects.create"]
  depends_on  = [google_project_service.api]
}
