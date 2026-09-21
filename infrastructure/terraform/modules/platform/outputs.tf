output "deployment" {
  description = "Non-secret deployment inputs; Helm still owns Kubernetes workloads."
  value = {
    project_id       = var.project_id
    region           = var.region
    cluster_name     = google_container_cluster.orders.name
    registry         = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.images.repository_id}"
    sql_connection   = google_sql_database_instance.platform.connection_name
    sql_private_ip   = google_sql_database_instance.platform.private_ip_address
    service_accounts = { for k, v in google_service_account.identity : k => v.email }
    secrets          = { for k, v in google_secret_manager_secret.database_password : k => v.secret_id }
    buckets          = { for k, v in google_storage_bucket.documents : k => v.name }
    cloud_run_urls   = { for k, v in google_cloud_run_v2_service.consumer : k => v.uri }
    api_address      = google_compute_global_address.api.address
    api_address_name = google_compute_global_address.api.name
    certificate_name = try(google_compute_managed_ssl_certificate.api[0].name, "")
  }
}
output "helm_platform_values" {
  description = "YAML fragment: merge with private release values supplying image, auth, and existing Secret."
  value = yamlencode({
    serviceAccount = { googleServiceAccount = google_service_account.identity["order-runtime"].email }
    database       = { instanceConnectionName = google_sql_database_instance.platform.connection_name, name = "orders", poolSize = 5, connectionBudget = 50 }
    app = {
      projectId             = var.project_id
      uploadBucket          = google_storage_bucket.documents["uploads"].name
      reportBucket          = google_storage_bucket.documents["reports"].name
      signingServiceAccount = google_service_account.identity["storage-signer"].email
    }
    networkPolicy = { sqlCidrs = ["${google_sql_database_instance.platform.private_ip_address}/32"] }
    ingress = {
      enabled              = var.api_hostname != ""
      host                 = var.api_hostname
      staticIpName         = google_compute_global_address.api.name
      preSharedCertificate = try(google_compute_managed_ssl_certificate.api[0].name, "")
    }
  })
}
