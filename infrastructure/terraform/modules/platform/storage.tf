resource "google_storage_bucket" "documents" {
  for_each                    = toset(["uploads", "reports"])
  project                     = var.project_id
  name                        = "${var.project_id}-${local.prefix}-${each.key}"
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false
  labels                      = local.labels
  versioning { enabled = true }
  # No automatic object deletion: report and inbox retention must cover controlled replay.
  lifecycle { prevent_destroy = true }
  depends_on = [google_project_service.api]
}
locals {
  object_grants = {
    order_upload_read      = { bucket = "uploads", identity = "order-runtime", role = "roles/storage.objectViewer" }
    signer_upload          = { bucket = "uploads", identity = "storage-signer", role = "roles/storage.objectCreator" }
    signer_report          = { bucket = "reports", identity = "storage-signer", role = "roles/storage.objectViewer" }
    document_upload        = { bucket = "uploads", identity = "document-runtime", role = "roles/storage.objectViewer" }
    document_report_read   = { bucket = "reports", identity = "document-runtime", role = "roles/storage.objectViewer" }
    document_report_create = { bucket = "reports", identity = "document-runtime", role = "roles/storage.objectCreator" }
  }
}
resource "google_storage_bucket_iam_member" "objects" {
  for_each = local.object_grants
  bucket   = google_storage_bucket.documents[each.value.bucket].name
  role     = each.value.role
  member   = "serviceAccount:${google_service_account.identity[each.value.identity].email}"
}
