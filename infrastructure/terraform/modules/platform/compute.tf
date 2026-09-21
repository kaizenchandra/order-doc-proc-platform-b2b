resource "google_container_cluster" "orders" {
  project             = var.project_id
  name                = local.prefix
  location            = var.region
  enable_autopilot    = true
  deletion_protection = true
  network             = google_compute_network.platform.id
  subnetwork          = google_compute_subnetwork.gke.id
  release_channel { channel = "REGULAR" }
  ip_allocation_policy {
    cluster_secondary_range_name  = "pods"
    services_secondary_range_name = "services"
  }
  private_cluster_config {
    enable_private_nodes    = true
    enable_private_endpoint = true
    master_ipv4_cidr_block  = "172.16.0.0/28"
  }
  workload_identity_config { workload_pool = "${var.project_id}.svc.id.goog" }
  cluster_autoscaling {
    auto_provisioning_defaults {
      service_account = google_service_account.identity["gke-node"].email
      oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]
    }
  }
  resource_labels = local.labels
  depends_on      = [google_project_iam_member.node, google_artifact_registry_repository_iam_member.node_pull]
}
resource "google_sql_database_instance" "platform" {
  project             = var.project_id
  name                = "${local.prefix}-postgres"
  region              = var.region
  database_version    = "POSTGRES_17"
  deletion_protection = true
  settings {
    tier                        = "db-custom-2-7680"
    edition                     = "ENTERPRISE"
    availability_type           = var.environment == "dev" ? "ZONAL" : "REGIONAL"
    disk_size                   = 20
    disk_autoresize             = true
    deletion_protection_enabled = true
    user_labels                 = local.labels
    ip_configuration {
      ipv4_enabled    = false
      private_network = google_compute_network.platform.id
      ssl_mode        = "ENCRYPTED_ONLY"
    }
    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      start_time                     = "20:00"
      transaction_log_retention_days = 7
      backup_retention_settings { retained_backups = 7 }
    }
    database_flags {
      name  = "max_connections"
      value = "200"
    }
    maintenance_window {
      day          = 7
      hour         = 21
      update_track = "stable"
    }
  }
  lifecycle { prevent_destroy = true }
  depends_on = [google_service_networking_connection.sql]
}
resource "google_sql_database" "service" {
  for_each = toset(["orders", "notifications"])
  project  = var.project_id
  name     = each.key
  instance = google_sql_database_instance.platform.name
  lifecycle { prevent_destroy = true }
}
