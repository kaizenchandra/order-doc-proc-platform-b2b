locals {
  topics = toset(["order-events", "document-requests", "document-results"])
  consumers = {
    document-requests      = { topic = "document-requests", service = "document", path = "/internal/pubsub/document-requests", ack = 240 }
    notification-orders    = { topic = "order-events", service = "notification", path = "/internal/pubsub/events", ack = 60 }
    notification-results   = { topic = "document-results", service = "notification", path = "/internal/pubsub/events", ack = 60 }
    order-document-results = { topic = "document-results", service = "order", path = "", ack = 60 }
  }
  publishers = {
    orders   = { topic = "order-events", identity = "order-runtime" }
    requests = { topic = "document-requests", identity = "order-runtime" }
    results  = { topic = "document-results", identity = "document-runtime" }
  }
}
resource "google_pubsub_topic" "events" {
  for_each = local.topics
  project  = var.project_id
  name     = each.key
  labels   = local.labels
  message_storage_policy { allowed_persistence_regions = [var.region] }
  depends_on = [google_project_service.api]
}
resource "google_pubsub_topic" "dead_letter" {
  for_each = local.consumers
  project  = var.project_id
  name     = "${each.key}-dead-letter"
  labels   = local.labels
  message_storage_policy { allowed_persistence_regions = [var.region] }
  depends_on = [google_project_service.api]
}
resource "google_pubsub_subscription" "dead_letter_inspection" {
  for_each                   = local.consumers
  project                    = var.project_id
  name                       = "${each.key}-dead-letter-inspection"
  topic                      = google_pubsub_topic.dead_letter[each.key].id
  message_retention_duration = "2678400s"
  expiration_policy { ttl = "" }
  labels = local.labels
}
resource "google_pubsub_topic_iam_member" "publisher" {
  for_each = local.publishers
  project  = var.project_id
  topic    = google_pubsub_topic.events[each.value.topic].name
  role     = "roles/pubsub.publisher"
  member   = "serviceAccount:${google_service_account.identity[each.value.identity].email}"
}
resource "google_pubsub_topic_iam_member" "dead_letter_publisher" {
  for_each = local.consumers
  project  = var.project_id
  topic    = google_pubsub_topic.dead_letter[each.key].name
  role     = "roles/pubsub.publisher"
  member   = "serviceAccount:${google_project_service_identity.agent["pubsub.googleapis.com"].email}"
}
resource "google_service_account_iam_member" "push_token" {
  for_each           = toset(["document", "notification"])
  service_account_id = google_service_account.identity["${each.key}-push"].name
  role               = "roles/iam.serviceAccountOpenIdTokenCreator"
  member             = "serviceAccount:${google_project_service_identity.agent["pubsub.googleapis.com"].email}"
}
resource "google_pubsub_subscription" "consumer" {
  for_each                   = local.consumers
  project                    = var.project_id
  name                       = each.key
  topic                      = google_pubsub_topic.events[each.value.topic].id
  ack_deadline_seconds       = each.value.ack
  message_retention_duration = "604800s"
  labels                     = local.labels
  expiration_policy { ttl = "" }
  retry_policy {
    minimum_backoff = "10s"
    maximum_backoff = "600s"
  }
  dead_letter_policy {
    dead_letter_topic     = google_pubsub_topic.dead_letter[each.key].id
    max_delivery_attempts = 10
  }
  # Foundation subscriptions retain events in pull mode until services are activated.
  dynamic "push_config" {
    for_each = var.enable_cloud_run && each.value.service != "order" ? [each.value] : []
    content {
      push_endpoint = "${google_cloud_run_v2_service.consumer[push_config.value.service].uri}${push_config.value.path}"
      oidc_token {
        service_account_email = google_service_account.identity["${push_config.value.service}-push"].email
        audience              = local.audiences[push_config.value.service]
      }
    }
  }
  depends_on = [google_cloud_run_v2_service_iam_member.invoker, google_service_account_iam_member.push_token, google_pubsub_topic_iam_member.dead_letter_publisher, google_pubsub_subscription.dead_letter_inspection]
}
resource "google_pubsub_subscription_iam_member" "dead_letter_subscriber" {
  for_each     = local.consumers
  project      = var.project_id
  subscription = google_pubsub_subscription.consumer[each.key].name
  role         = "roles/pubsub.subscriber"
  member       = "serviceAccount:${google_project_service_identity.agent["pubsub.googleapis.com"].email}"
}
resource "google_pubsub_subscription_iam_member" "order_results" {
  project      = var.project_id
  subscription = google_pubsub_subscription.consumer["order-document-results"].name
  role         = "roles/pubsub.subscriber"
  member       = "serviceAccount:${google_service_account.identity["order-runtime"].email}"
}
