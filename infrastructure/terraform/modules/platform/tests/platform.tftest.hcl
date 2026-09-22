# No credentials or GCP calls: both providers are mocked for every run.
mock_provider "google" {
  mock_resource "google_project_iam_custom_role" {
    defaults = { name = "projects/test-platform/roles/mockRole" }
  }
  mock_resource "google_compute_network" {
    defaults = { id = "projects/test-platform/global/networks/orders-dev" }
  }
  override_resource {
    target = google_service_account.identity["order-runtime"]
    values = {
      name  = "projects/test-platform/serviceAccounts/order-runtime@test-platform.iam.gserviceaccount.com"
      email = "order-runtime@test-platform.iam.gserviceaccount.com"
    }
  }
  override_resource {
    target = google_service_account.identity["document-runtime"]
    values = {
      name  = "projects/test-platform/serviceAccounts/document-runtime@test-platform.iam.gserviceaccount.com"
      email = "document-runtime@test-platform.iam.gserviceaccount.com"
    }
  }
  override_resource {
    target = google_service_account.identity["notification-runtime"]
    values = {
      name  = "projects/test-platform/serviceAccounts/notification-runtime@test-platform.iam.gserviceaccount.com"
      email = "notification-runtime@test-platform.iam.gserviceaccount.com"
    }
  }
  override_resource {
    target = google_service_account.identity["document-push"]
    values = {
      name  = "projects/test-platform/serviceAccounts/document-push@test-platform.iam.gserviceaccount.com"
      email = "document-push@test-platform.iam.gserviceaccount.com"
    }
  }
  override_resource {
    target = google_service_account.identity["notification-push"]
    values = {
      name  = "projects/test-platform/serviceAccounts/notification-push@test-platform.iam.gserviceaccount.com"
      email = "notification-push@test-platform.iam.gserviceaccount.com"
    }
  }
  override_resource {
    target = google_service_account.identity["storage-signer"]
    values = {
      name  = "projects/test-platform/serviceAccounts/storage-signer@test-platform.iam.gserviceaccount.com"
      email = "storage-signer@test-platform.iam.gserviceaccount.com"
    }
  }
  override_resource {
    target = google_service_account.identity["gke-node"]
    values = {
      name  = "projects/test-platform/serviceAccounts/gke-node@test-platform.iam.gserviceaccount.com"
      email = "gke-node@test-platform.iam.gserviceaccount.com"
    }
  }
  mock_resource "google_sql_database_instance" {
    defaults = {
      connection_name    = "test-platform:asia-south1:orders-dev-postgres"
      private_ip_address = "10.30.0.3"
    }
  }
  mock_resource "google_cloud_run_v2_service" {
    defaults = { uri = "https://mock-service.run.app" }
  }
}
mock_provider "google-beta" {}
variables {
  project_id  = "test-platform"
  environment = "dev"
}
run "foundation" {
  command = plan
  assert {
    condition     = length(google_cloud_run_v2_service.consumer) == 0 && length(google_pubsub_subscription.consumer) == 4
    error_message = "Foundation must retain all four subscriptions without starting unprepared services."
  }
  assert {
    condition     = alltrue([for s in google_pubsub_subscription.consumer : length(s.push_config) == 0 && s.expiration_policy[0].ttl == ""])
    error_message = "Foundation subscriptions must be durable pull subscriptions."
  }
  assert {
    condition     = google_sql_database_instance.platform.settings[0].ip_configuration[0].ipv4_enabled == false && google_sql_database_instance.platform.settings[0].backup_configuration[0].point_in_time_recovery_enabled
    error_message = "SQL must be private and recoverable."
  }
  assert {
    condition     = google_container_cluster.orders.enable_autopilot && google_container_cluster.orders.private_cluster_config[0].enable_private_endpoint && google_container_cluster.orders.deletion_protection
    error_message = "GKE must use protected Autopilot with a private control-plane endpoint."
  }
  assert {
    condition     = alltrue([for b in google_storage_bucket.documents : b.public_access_prevention == "enforced" && b.uniform_bucket_level_access && !b.force_destroy])
    error_message = "Document buckets must not expose public access or force-delete documents."
  }
  assert {
    condition     = alltrue([for s in google_service_account.identity : length(s.account_id) <= 30])
    error_message = "Service-account names must meet Google's length limit."
  }
}
run "activated" {
  # Mock apply resolves endpoint/identity values and dependency-ordered push settings.
  command = apply
  variables {
    enable_cloud_run              = true
    database_prepared             = true
    notification_password_version = "3"
    images = {
      document     = "asia-south1-docker.pkg.dev/test-platform/orders-dev/document@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      notification = "asia-south1-docker.pkg.dev/test-platform/orders-dev/notification@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
  }
  assert {
    condition     = alltrue([for s in google_cloud_run_v2_service.consumer : s.ingress == "INGRESS_TRAFFIC_INTERNAL_ONLY" && !s.invoker_iam_disabled && s.deletion_protection])
    error_message = "Cloud Run must preserve internal ingress, IAM checks, and deletion protection."
  }
  assert {
    condition = alltrue([for k, s in google_pubsub_subscription.consumer : k == "order-document-results" ? length(s.push_config) == 0 : (
      s.push_config[0].oidc_token[0].audience == google_cloud_run_v2_service.consumer[local.consumers[k].service].custom_audiences[0] &&
      s.push_config[0].oidc_token[0].service_account_email != google_cloud_run_v2_service.consumer[local.consumers[k].service].template[0].service_account
    )])
    error_message = "Push audiences must match and push identities must differ from runtimes; order remains pull."
  }
  assert {
    condition = alltrue([for k, s in google_cloud_run_v2_service.consumer :
      s.template[0].containers[0].startup_probe[0].http_get[0].path == "/readyz" &&
      s.template[0].containers[0].liveness_probe[0].http_get[0].path == "/livez"
    ])
    error_message = "Use application readiness at startup and dependency-independent liveness."
  }
  assert {
    condition     = length(google_cloud_run_v2_service.consumer["document"].template[0].vpc_access) == 0 && length([for e in google_cloud_run_v2_service.consumer["document"].template[0].containers[0].env : e if startswith(e.name, "DB_")]) == 0
    error_message = "The document worker must not gain SQL configuration or private VPC access."
  }
  assert {
    condition     = one([for e in google_cloud_run_v2_service.consumer["notification"].template[0].containers[0].env : e if e.name == "DB_PASSWORD"]).value_source[0].secret_key_ref[0].version == "3"
    error_message = "Database credentials must use the pinned Secret Manager version."
  }
  assert {
    condition     = length(google_pubsub_subscription.dead_letter_inspection) == 4 && length(google_pubsub_subscription_iam_member.dead_letter_subscriber) == 4 && length(google_pubsub_topic_iam_member.dead_letter_publisher) == 4
    error_message = "Every consumer needs a retained dead-letter subscription and both forwarding grants."
  }
  assert {
    condition     = alltrue([for grant in google_cloud_run_v2_service_iam_member.invoker : startswith(grant.member, "serviceAccount:") && grant.role == "roles/run.invoker"])
    error_message = "Invoker grants must never use public principals."
  }
}
run "least_privilege" {
  command = plan
  assert {
    condition = (
      toset(google_project_iam_custom_role.blob_signer.permissions) == toset(["iam.serviceAccounts.signBlob"]) &&
      toset(google_project_iam_custom_role.object_read.permissions) == toset(["storage.objects.get"]) &&
      toset(google_project_iam_custom_role.object_create.permissions) == toset(["storage.objects.create"])
    )
    error_message = "Signing/object custom roles must not grow token, listing, deletion, or administration permissions."
  }
  assert {
    condition = alltrue([for grant in google_service_account_iam_member.push_token :
      grant.role == "roles/iam.serviceAccountOpenIdTokenCreator"
    ])
    error_message = "Pub/Sub needs only OIDC token creation, not general impersonation."
  }
  assert {
    condition     = toset(keys(google_project_iam_member.sql_client)) == toset(["order-runtime", "notification-runtime"]) && length(google_secret_manager_secret_iam_member.notification_password) > 0
    error_message = "Document and push identities must not gain SQL access."
  }
}
run "production" {
  command = plan
  variables { environment = "prod" }
  assert {
    condition     = google_sql_database_instance.platform.settings[0].availability_type == "REGIONAL" && google_sql_database_instance.platform.settings[0].deletion_protection_enabled
    error_message = "Production SQL requires HA and API deletion protection."
  }
}
run "staging_names" {
  command = plan
  variables { environment = "staging" }
  assert {
    condition     = alltrue([for s in google_service_account.identity : length(s.account_id) <= 30]) && google_sql_database_instance.platform.settings[0].availability_type == "REGIONAL"
    error_message = "Staging identities must be valid and SQL must match production HA."
  }
}
run "reject_unprepared_activation" {
  command = plan
  variables { enable_cloud_run = true }
  expect_failures = [var.images, var.notification_password_version, var.database_prepared]
}
run "reject_mutable_image" {
  command = plan
  variables {
    enable_cloud_run              = true
    database_prepared             = true
    notification_password_version = "1"
    images                        = { document = "example:latest", notification = "example:latest" }
  }
  expect_failures = [var.images]
}
run "reject_unpinned_secret" {
  command = plan
  variables {
    enable_cloud_run              = true
    database_prepared             = true
    notification_password_version = "latest"
    images = {
      document     = "asia-south1-docker.pkg.dev/test-platform/orders-dev/document@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      notification = "asia-south1-docker.pkg.dev/test-platform/orders-dev/notification@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
  }
  expect_failures = [var.notification_password_version]
}

run "monitoring_contract" {
  command = plan
  assert {
    condition     = length(google_monitoring_alert_policy.subscription) == 8 && length(google_logging_metric.signal) == 4 && length(google_monitoring_alert_policy.signal) == 4 && google_monitoring_alert_policy.sampler_absent.conditions[0].condition_absent[0].duration == "300s"
    error_message = "All consumers need backlog/DLQ coverage and SQL/application recovery signals."
  }
  assert {
    condition     = toset([for label in google_logging_metric.operations.metric_descriptor[0].labels : label.key]) == toset(["operation", "outcome"])
    error_message = "Do not introduce tenant/event/trace identifiers as metric labels."
  }
}

run "restore_replay_window" {
  command = plan
  assert {
    condition     = alltrue([for s in google_pubsub_subscription.consumer : s.retain_acked_messages && s.message_retention_duration == "604800s"])
    error_message = "All four consumers must retain acknowledged messages for post-restore replay."
  }
  assert {
    condition     = alltrue([for b in google_storage_bucket.documents : b.versioning[0].enabled && length(b.lifecycle_rule) == 0])
    error_message = "Input generations and canonical reports must survive the replay window; no automatic deletion is configured."
  }
}
