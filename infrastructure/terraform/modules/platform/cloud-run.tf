locals {
  audiences = { for s in ["document", "notification"] : s => "https://pubsub.${var.project_id}.internal/${var.environment}/${s}" }
  run_env = {
    document = {
      SPRING_PROFILES_ACTIVE         = "cloud-run"
      PUSH_AUDIENCE                  = local.audiences.document
      PUSH_SERVICE_ACCOUNT_EMAIL     = google_service_account.identity["document-push"].email
      STORAGE_ENABLED                = "true"
      MESSAGING_ENABLED              = "true"
      PUBSUB_PROJECT_ID              = var.project_id
      PUBSUB_EMULATOR_HOST           = ""
      UPLOAD_BUCKET                  = google_storage_bucket.documents["uploads"].name
      REPORT_BUCKET                  = google_storage_bucket.documents["reports"].name
      DOCUMENT_RESULTS_TOPIC         = "document-results"
      DOCUMENT_REQUESTS_SUBSCRIPTION = "projects/${var.project_id}/subscriptions/document-requests"
    }
    notification = {
      SPRING_PROFILES_ACTIVE           = "cloud-run"
      PUSH_AUDIENCE                    = local.audiences.notification
      PUSH_SERVICE_ACCOUNT_EMAIL       = google_service_account.identity["notification-push"].email
      NOTIFICATION_EVENTS_SUBSCRIPTION = "projects/${var.project_id}/subscriptions/notification-orders,projects/${var.project_id}/subscriptions/notification-results"
      DB_MIGRATIONS_ENABLED            = "false"
      DB_POOL_SIZE                     = "5"
      DB_USERNAME                      = "notifications"
      DB_URL                           = "jdbc:postgresql:///notifications?cloudSqlInstance=${google_sql_database_instance.platform.connection_name}&socketFactory=com.google.cloud.sql.postgres.SocketFactory&ipTypes=PRIVATE&cloudSqlRefreshStrategy=lazy&connectTimeout=5&socketTimeout=20"
    }
  }
}
resource "google_cloud_run_v2_service" "consumer" {
  for_each             = var.enable_cloud_run ? toset(["document", "notification"]) : toset([])
  project              = var.project_id
  name                 = "${local.prefix}-${each.key}"
  location             = var.region
  ingress              = "INGRESS_TRAFFIC_INTERNAL_ONLY"
  invoker_iam_disabled = false
  deletion_protection  = true
  custom_audiences     = [local.audiences[each.key]]
  labels               = local.labels
  template {
    service_account                  = google_service_account.identity["${each.key}-runtime"].email
    execution_environment            = "EXECUTION_ENVIRONMENT_GEN2"
    timeout                          = each.key == "document" ? "180s" : "30s"
    max_instance_request_concurrency = each.key == "document" ? 2 : 5
    scaling {
      min_instance_count = 0
      max_instance_count = 4
    }
    dynamic "vpc_access" {
      for_each = each.key == "notification" ? [1] : []
      content {
        egress = "PRIVATE_RANGES_ONLY"
        network_interfaces {
          network    = google_compute_network.platform.name
          subnetwork = google_compute_subnetwork.run.name
        }
      }
    }
    containers {
      image = lookup(var.images, each.key, "")
      ports { container_port = 8080 }
      resources {
        limits            = { cpu = "1", memory = "1Gi" }
        cpu_idle          = true
        startup_cpu_boost = true
      }
      startup_probe {
        period_seconds    = 5
        timeout_seconds   = 2
        failure_threshold = 36
        http_get {
          path = "/readyz"
          port = 8080
        }
      }
      liveness_probe {
        period_seconds    = 10
        timeout_seconds   = 2
        failure_threshold = 3
        http_get {
          path = "/livez"
          port = 8080
        }
      }
      dynamic "env" {
        for_each = local.run_env[each.key]
        content {
          name  = env.key
          value = env.value
        }
      }
      dynamic "env" {
        for_each = each.key == "notification" ? [1] : []
        content {
          name = "DB_PASSWORD"
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.database_password["notification"].secret_id
              version = var.notification_password_version
            }
          }
        }
      }
    }
  }
  traffic {
    type    = "TRAFFIC_TARGET_ALLOCATION_TYPE_LATEST"
    percent = 100
  }
  depends_on = [google_project_service_identity.agent, google_secret_manager_secret_iam_member.notification_password, google_project_iam_member.sql_client, google_storage_bucket_iam_member.objects, google_pubsub_topic_iam_member.publisher]
}
resource "google_cloud_run_v2_service_iam_member" "invoker" {
  for_each = google_cloud_run_v2_service.consumer
  project  = var.project_id
  location = var.region
  name     = each.value.name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.identity["${each.key}-push"].email}"
}
