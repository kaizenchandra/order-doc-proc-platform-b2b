# Dedicated project/VPC per environment; ranges are intentionally fixed and disjoint.
resource "google_compute_network" "platform" {
  project                 = var.project_id
  name                    = local.prefix
  auto_create_subnetworks = false
  depends_on              = [google_project_service.api]
}
resource "google_compute_subnetwork" "gke" {
  project                  = var.project_id
  name                     = "${local.prefix}-gke"
  region                   = var.region
  network                  = google_compute_network.platform.id
  ip_cidr_range            = "10.20.0.0/20"
  private_ip_google_access = true
  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = "10.24.0.0/14"
  }
  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = "10.28.0.0/20"
  }
}
resource "google_compute_subnetwork" "run" {
  project                  = var.project_id
  name                     = "${local.prefix}-run"
  region                   = var.region
  network                  = google_compute_network.platform.id
  ip_cidr_range            = "10.20.16.0/24"
  private_ip_google_access = true
}
resource "google_compute_global_address" "sql_range" {
  project       = var.project_id
  name          = "${local.prefix}-sql-range"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  address       = "10.30.0.0"
  prefix_length = 16
  network       = google_compute_network.platform.id
}
resource "google_service_networking_connection" "sql" {
  network                 = google_compute_network.platform.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.sql_range.name]
  deletion_policy         = "ABANDON"
  depends_on              = [google_project_service.api]
}
resource "google_compute_router" "egress" {
  project = var.project_id
  name    = local.prefix
  region  = var.region
  network = google_compute_network.platform.id
}
resource "google_compute_router_nat" "egress" {
  project                            = var.project_id
  name                               = local.prefix
  region                             = var.region
  router                             = google_compute_router.egress.name
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "LIST_OF_SUBNETWORKS"
  subnetwork {
    name                    = google_compute_subnetwork.gke.id
    source_ip_ranges_to_nat = ["ALL_IP_RANGES"]
  }
}
resource "google_compute_global_address" "api" {
  project    = var.project_id
  name       = "${local.prefix}-api"
  depends_on = [google_project_service.api]
}
resource "google_compute_managed_ssl_certificate" "api" {
  count   = var.api_hostname == "" ? 0 : 1
  project = var.project_id
  name    = "${local.prefix}-api"
  managed { domains = [var.api_hostname] }
  depends_on = [google_project_service.api]
}
resource "google_dns_record_set" "api" {
  count        = var.dns_managed_zone == "" ? 0 : 1
  project      = var.project_id
  managed_zone = var.dns_managed_zone
  name         = "${var.api_hostname}."
  type         = "A"
  ttl          = 300
  rrdatas      = [google_compute_global_address.api.address]
}
