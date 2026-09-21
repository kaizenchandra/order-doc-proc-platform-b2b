locals {
  log_signals = {
    operation_failures = {
      filter    = "jsonPayload.event=\"operation_completed\" AND jsonPayload.outcome=\"failed\""
      threshold = 4
      resources = ["k8s_container", "cloud_run_revision"]
      response  = "Inspect operation and trace_id in structured logs. Check SQL, IAM, and publisher failures. Do not ACK failed work or delete inbox/report records."
    }
    outbox_overdue = {
      filter    = "jsonPayload.event=\"queue_sample\" AND jsonPayload.outbox_oldest_seconds>300"
      threshold = 0
      resources = ["k8s_container"]
      response  = "Inspect unpublished outbox age, attempt_count, last_error_code, and relay readiness. Repair the publisher or SQL dependency; preserve event IDs and leases."
    }
    queued_overdue = {
      filter    = "jsonPayload.event=\"queue_sample\" AND jsonPayload.queued_oldest_seconds>900"
      threshold = 0
      resources = ["k8s_container"]
      response  = "Reconcile aged QUEUED documents against request/result subscriptions and canonical reports. Replay only after identifying the failed boundary."
    }
    sampler_failed = {
      filter    = "jsonPayload.event=\"queue_sample_failed\""
      threshold = 2
      resources = ["k8s_container"]
      response  = "Queue age is unknown, not zero. Check database availability, pool pressure, query duration, and sampling logs."
    }
  }
  subscription_alerts = merge(
    { for name in keys(local.consumers) : "${name}-age" => {
      subscription = name, metric = "oldest_unacked_message_age", threshold = 600, duration = "300s"
    } },
    { for name in keys(local.consumers) : "${name}-dlq" => {
      subscription = "${name}-dead-letter-inspection", metric = "num_undelivered_messages", threshold = 0, duration = "60s"
    } }
  )
}
resource "google_logging_metric" "operations" {
  project = var.project_id
  name    = "${local.prefix}/operations"
  filter  = "(resource.type=\"k8s_container\" OR resource.type=\"cloud_run_revision\") AND jsonPayload.event=\"operation_completed\""
  metric_descriptor {
    metric_kind = "DELTA"
    value_type  = "INT64"
    unit        = "1"
    labels {
      key        = "operation"
      value_type = "STRING"
    }
    labels {
      key        = "outcome"
      value_type = "STRING"
    }
  }
  label_extractors = {
    operation = "EXTRACT(jsonPayload.operation)"
    outcome   = "EXTRACT(jsonPayload.outcome)"
  }
  depends_on = [google_project_service.api]
}
resource "google_logging_metric" "signal" {
  for_each = local.log_signals
  project  = var.project_id
  name     = "${local.prefix}/${each.key}"
  filter   = "(resource.type=\"k8s_container\" OR resource.type=\"cloud_run_revision\") AND ${each.value.filter}"
  metric_descriptor {
    metric_kind = "DELTA"
    value_type  = "INT64"
    unit        = "1"
  }
  depends_on = [google_project_service.api]
}
resource "google_monitoring_alert_policy" "signal" {
  for_each              = local.log_signals
  project               = var.project_id
  display_name          = "${local.prefix}: ${each.key}"
  combiner              = "OR"
  notification_channels = var.notification_channels
  user_labels           = local.labels
  severity              = "WARNING"
  dynamic "conditions" {
    for_each = toset(each.value.resources)
    content {
      display_name = "${each.key} on ${conditions.value}"
      condition_threshold {
        filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.signal[each.key].name}\" AND resource.type=\"${conditions.value}\""
        comparison      = "COMPARISON_GT"
        threshold_value = each.value.threshold
        duration        = "0s"
        aggregations {
          alignment_period   = "300s"
          per_series_aligner = "ALIGN_SUM"
        }
        trigger { count = 1 }
      }
    }
  }
  documentation {
    content   = "${each.value.response} See docs/operations.md in the deployed release repository. Values are per resource instance over five minutes; duplicate attempts and multiple replicas are expected."
    mime_type = "text/markdown"
  }
}
resource "google_monitoring_alert_policy" "subscription" {
  for_each              = local.subscription_alerts
  project               = var.project_id
  display_name          = "${local.prefix}: ${each.key}"
  combiner              = "OR"
  notification_channels = var.notification_channels
  user_labels           = local.labels
  severity              = "WARNING"
  conditions {
    display_name = "Subscription backlog requires investigation"
    condition_threshold {
      filter          = "metric.type=\"pubsub.googleapis.com/subscription/${each.value.metric}\" AND resource.type=\"pubsub_subscription\" AND resource.label.subscription_id=\"${each.value.subscription}\""
      comparison      = "COMPARISON_GT"
      threshold_value = each.value.threshold
      duration        = each.value.duration
      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_MAX"
      }
      trigger { count = 1 }
      evaluation_missing_data = "EVALUATION_MISSING_DATA_INACTIVE"
    }
  }
  documentation {
    content   = "Inspect ${each.value.subscription}, consumer errors, IAM, and database health. DLQ wrappers must be decoded and reviewed before replay. Preserve original domain event IDs. Pub/Sub metrics can lag by several minutes; missing data is not evidence of recovery. See docs/operations.md."
    mime_type = "text/markdown"
  }
  depends_on = [google_project_service.api, google_pubsub_subscription.consumer, google_pubsub_subscription.dead_letter_inspection]
}
resource "google_logging_metric" "queue_samples" {
  project = var.project_id
  name    = "${local.prefix}/queue_samples"
  filter  = "resource.type=\"k8s_container\" AND jsonPayload.service=\"order-service\" AND jsonPayload.event=\"queue_sample\""
  metric_descriptor {
    metric_kind = "DELTA"
    value_type  = "INT64"
    unit        = "1"
  }
  depends_on = [google_project_service.api]
}
resource "google_monitoring_alert_policy" "sampler_absent" {
  project               = var.project_id
  display_name          = "${local.prefix}: queue sampling absent"
  combiner              = "OR"
  notification_channels = var.notification_channels
  user_labels           = local.labels
  severity              = "WARNING"
  conditions {
    display_name = "No successful sample from any order replica for five minutes"
    condition_absent {
      filter   = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.queue_samples.name}\" AND resource.type=\"k8s_container\""
      duration = "300s"
      aggregations {
        alignment_period     = "60s"
        per_series_aligner   = "ALIGN_RATE"
        cross_series_reducer = "REDUCE_SUM"
      }
      trigger { count = 1 }
    }
  }
  documentation {
    content   = "Check order replicas, queue_sample_failed logs, database/query health, and log ingestion. Absence detection requires a previously observed series; validate first deployment separately. See docs/operations.md."
    mime_type = "text/markdown"
  }
}
