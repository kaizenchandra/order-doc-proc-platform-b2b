locals {
  prefix = "orders-${var.environment}"
  labels = { application = "order-platform", environment = var.environment, managed_by = "terraform" }
  apis = toset([
    "compute.googleapis.com", "container.googleapis.com", "sqladmin.googleapis.com",
    "servicenetworking.googleapis.com", "artifactregistry.googleapis.com", "run.googleapis.com",
    "pubsub.googleapis.com", "secretmanager.googleapis.com", "iam.googleapis.com",
    "iamcredentials.googleapis.com", "storage.googleapis.com", "dns.googleapis.com",
    "logging.googleapis.com", "monitoring.googleapis.com"
  ])
}
resource "google_project_service" "api" {
  for_each           = local.apis
  project            = var.project_id
  service            = each.key
  disable_on_destroy = false
}
resource "google_project_service_identity" "agent" {
  provider   = google-beta
  for_each   = toset(["pubsub.googleapis.com", "run.googleapis.com"])
  project    = var.project_id
  service    = each.key
  depends_on = [google_project_service.api]
}
resource "google_service_account" "identity" {
  for_each     = toset(["order-runtime", "document-runtime", "notification-runtime", "document-push", "notification-push", "storage-signer", "gke-node"])
  project      = var.project_id
  account_id   = "op-${var.environment}-${replace(each.key, "notification", "notify")}"
  display_name = "${var.environment} ${each.key}"
  depends_on   = [google_project_service.api]
}
resource "google_artifact_registry_repository" "images" {
  project       = var.project_id
  location      = var.region
  repository_id = local.prefix
  format        = "DOCKER"
  labels        = local.labels
  depends_on    = [google_project_service.api]
}
resource "google_artifact_registry_repository_iam_member" "node_pull" {
  project    = var.project_id
  location   = var.region
  repository = google_artifact_registry_repository.images.name
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.identity["gke-node"].email}"
}
resource "google_project_iam_member" "node" {
  project = var.project_id
  role    = "roles/container.defaultNodeServiceAccount"
  member  = "serviceAccount:${google_service_account.identity["gke-node"].email}"
}
resource "google_service_account_iam_member" "order_workload" {
  service_account_id = google_service_account.identity["order-runtime"].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[order-platform/orders-order]"
  depends_on         = [google_container_cluster.orders]
}
resource "google_service_account_iam_member" "sign_blob" {
  service_account_id = google_service_account.identity["storage-signer"].name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:${google_service_account.identity["order-runtime"].email}"
}
resource "google_project_iam_member" "sql_client" {
  for_each = toset(["order-runtime", "notification-runtime"])
  project  = var.project_id
  role     = "roles/cloudsql.client"
  member   = "serviceAccount:${google_service_account.identity[each.key].email}"
}
resource "google_secret_manager_secret" "database_password" {
  for_each  = toset(["order", "notification"])
  project   = var.project_id
  secret_id = "${local.prefix}-${each.key}-db-password"
  labels    = local.labels
  replication {
    auto {}
  }
  lifecycle { prevent_destroy = true }
  depends_on = [google_project_service.api]
}
resource "google_secret_manager_secret_iam_member" "notification_password" {
  project   = var.project_id
  secret_id = google_secret_manager_secret.database_password["notification"].secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.identity["notification-runtime"].email}"
}
