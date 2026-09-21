# Phase 15 — Resilience and observability

The services now emit structured operational events, preserve trace correlation in logs, sample durable queue age, and
include messaging-worker state in order-service readiness. Terraform adds log-based metrics and thirteen alert policies.
Existing retry, idempotency, and commit boundaries remain unchanged. No cloud alerts have been applied or fired as part
of this phase.

## Signals and health

All three applications write Logstash JSON to stdout, with a `service` field and `severity` mapped from the log level.
Spring Boot includes MDC and SLF4J key/value fields in this format.
[Structured logging](https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured)

An `operation_completed` record contains only `operation`, `outcome`, and `duration_ms`, alongside logger/service/trace
metadata. The operation enum has four values:

| Operation | `succeeded` means | Failure/recovery |
| --- | --- | --- |
| OUTBOX_PUBLISH | Publisher accepted the message | Backoff retains the event; marking the SQL lease published happens afterward and may still fail |
| ORDER_RESULT | Handler transaction returned after commit, including a harmless duplicate/stale result | NACK on decode/transaction failure; ACK is attempted after observation |
| DOCUMENT_PUSH | Canonical report persisted/reused and stable result published | Non-2xx on transient failure; redelivery reuses the canonical result |
| NOTIFICATION_PUSH | Inbox/audit/notification transaction committed, including a duplicate no-op | Non-2xx on failure; transaction retry remains broker-owned |

These measure attempts, not unique business effects or confirmed broker ACK delivery. Document failure reports are
successful pipeline operations when durably stored and published. Malformed wrappers/oversized pushes fail before the
operation observation; use Cloud Run request logs and subscription metrics for those cases. Do not interpret an
operation failure count as an end-to-end error rate without selecting the correct denominator.

Validated W3C v00 `traceparent` values propagate through the existing outbox/Pub/Sub flow. `TraceContext` now places
`trace_id` and `span_id` in MDC and restores the prior context on close, including nested scopes and reused threads.
Identifiers stay in logs, never metric labels. Incoming trace context is correlation data, not authentication evidence.
Requests without valid trace context have no trace fields. This implementation does not generate/export distributed
spans or claim a Cloud Trace service graph; end-to-end span instrumentation remains a separate integration.

Only status-only health endpoints are exposed. Order readiness now includes `messaging`: when enabled, a stopped
subscriber, shutdown scheduler, or completed/cancelled relay scheduling task makes readiness DOWN. Disabled local/test
messaging does not fail readiness. Liveness remains independent of SQL/Pub/Sub availability. A terminal worker failure
needs a controlled pod restart after its cause is understood; this phase does not repeatedly restart failed subscribers
inside the process. The health check detects lifecycle failure, not a hung in-flight call or missing business progress.
Queue-age alerts cover that separate concern.

## Durable queue sampling

When order messaging is enabled, a separate Spring scheduled task samples every 30 seconds after a 30-second initial
delay. It emits `queue_sample` with `outbox_oldest_seconds` and `queued_oldest_seconds` from PostgreSQL's clock. An empty
queue is zero. Samples query only unpublished outbox rows and documents currently QUEUED; they never modify, lease,
republish, or delete records.

Each query has a two-second statement timeout via a dedicated JdbcTemplate using the existing connection pool. Pool
acquisition has the application's configured timeout. Pending-outbox age may scan the pending set; observe query cost
under load and add an appropriate migration/index if needed. Each replica samples the same database, so aggregate by
maximum/latest age rather than summing ages. Failures emit `queue_sample_failed`; they are not reported as zero age and
do not terminate the sampler or application. No query result contains payloads or customer fields.

## Metrics and alerts

Terraform defines six log-based counter metrics: completed operations (labels `operation` and `outcome` only), failed
operations, overdue outbox samples, overdue QUEUED samples, failed samples, and successful samples. Age values and
durations remain numeric log fields; these counters do not masquerade as latency histograms or age gauges. Logs Explorer
can inspect individual timing samples, and the operation counter supports attempt-rate dashboards.

| Policies | Starting condition | Response |
| --- | --- | --- |
| Four source backlog alerts | Oldest unacked age >600 seconds for 5 minutes | Check subscriber health, throughput, IAM and dependency failures |
| Four DLQ alerts | Inspection subscription backlog >0 for 1 minute | Triage poison/failed delivery before retention expires |
| Operation failures | At least 5 failures per resource instance in a 5-minute window | Inspect operation/trace, dependency errors, and recent revisions |
| Outbox overdue | A sample reports an unpublished event older than 300 seconds | Inspect relay, destination IAM, event encoding, and leases |
| QUEUED overdue | A sample reports a QUEUED document older than 900 seconds | Reconcile request, canonical report, result delivery, and result application |
| Sampler failures | At least 3 failures per pod in a 5-minute window | Check SQL, pool pressure, and query duration |
| Sampler absent | No successful sample across order replicas for 5 minutes | Check workload health and logging ingestion; distinguish missing data from zero |

The first sample must exist before absence detection can detect its disappearance; validate first-deployment telemetry
explicitly. Cloud Run scales to zero, so there is no unconditional Cloud Run heartbeat alert. Backlog gauges can lag or
have gaps. Alerts deliberately use thresholds with tolerances, and missing backlog data is not itself proof of health.
[Pub/Sub monitoring](https://docs.cloud.google.com/pubsub/docs/monitoring)

Set `notification_channels` in the environment tfvars to existing verified Cloud Monitoring channel resource names.
The default empty list creates console incidents only: it does not page anyone. Assign operational ownership and test
notification delivery before production. Policies use dedicated-project subscription names and the environment metric
prefix; do not share a project between these environment roots. Provisioning backlogs can trigger alerts while services
are intentionally disabled; manage maintenance/snoozes through the operational workflow. Metric/log storage and alerting
have platform costs; set retention and budgets as part of production rollout.

## Initial service objectives

Use these as candidate objectives to measure and tune, not claims established by local tests:

- API: 99.9% availability for valid authenticated requests over 30 days, excluding intentional client 4xx responses;
  track latency percentiles separately from availability.
- Processing: 99% of accepted document completions reach a durable PROCESSED/FAILED terminal state within five minutes.
  A valid terminal business failure is pipeline completion; unresolved QUEUED work is not.
- Recovery: restore backlog progress within the seven-day source retention window and resolve DLQ entries within their
  31-day inspection retention. Operational response targets must be much shorter than these data-loss boundaries.

The current alert thresholds are initial symptoms, not burn-rate SLO policies. Cloud request metrics, Kubernetes pod
metrics, Cloud SQL connection/CPU/storage metrics, and the new application signals should be viewed together. Measure
production baselines before changing concurrency, instance caps, pool size, or the shared SQL connection budget.

## Incident procedures

### Unpublished outbox

Run bounded, read-only queries with an authorized operational SQL identity. Do not copy result payloads into tickets:

```sql
SELECT event_id, destination, attempt_count, last_error_code, occurred_at, available_at, lease_until
FROM outbox_events
WHERE published_at IS NULL
ORDER BY occurred_at
LIMIT 100;
```

If the destination is unavailable or permissions changed, repair that dependency and let existing backoff/lease expiry
retry. Publication is outside the SQL transaction; a crash after publish but before the published marker can duplicate
an event. Preserve its eventId. Never clear leases indiscriminately while workers may own them. Invalid event versions
need a compatible consumer/producer fix or a reviewed migration; repeated retry cannot make an unsupported event valid.
A SQL failure after publish is investigated separately from a successful OUTBOX_PUBLISH log.

### Aged QUEUED document

```sql
SELECT id, tenant_id, order_id, processing_request_id, queued_at
FROM order_documents
WHERE status = 'QUEUED' AND queued_at < clock_timestamp() - interval '15 minutes'
ORDER BY queued_at
LIMIT 100;
```

Inspect request-subscription age/DLQ and document worker failures. Using the authorized operational identity, check the
canonical report for the same processingRequestId and exact registered input generation. If a report exists, replaying
the original processing request safely republishes its stable result even when the original upload is gone. If result
publication succeeded, inspect the independent order result subscription and transaction failures. Notification is an
independent consumer; repairing it must not consume or replace the order subscription. Do not manually set PROCESSED,
create a fresh event ID, or invent a new processingRequestId to conceal a stuck operation.

### Dead-letter review and replay

1. Identify the source subscription, failing version/type, first occurrence, and dependency/error category. Review only
   necessary metadata; do not expose document bodies or signed URLs.
2. Restore the consumer/dependency and verify a small known-good message. Preserve the original DLQ message until the
   review is complete. Pull without automatic acknowledgement for inspection.
3. Pub/Sub forwards a wrapper around the original message. Decode that wrapper and recover the original application
   envelope and relevant attributes. Publishing the DLQ wrapper directly to an application topic is invalid.
4. Replay the original event to its original topic with the same domain eventId, processingRequestId, and correlation
   metadata. Topic replay reaches every subscription; all affected consumers must retain compatible inbox/report state.
   Do not seek or reset a production subscription as an ad hoc replay mechanism.
5. Verify canonical report/state and one notification effect per event. Acknowledge the retained inspection message only
   after successful resolution and record the authorized replay outcome. Stop on renewed failures; do not automate an
   unbounded DLQ-to-source loop.

[Dead-letter wrappers and permissions](https://docs.cloud.google.com/pubsub/docs/dead-letter-topics)

### Dependency outage or failed worker

Check `/readyz`, worker state, platform logs, SQL private connectivity/pool wait, and Pub/Sub permission errors. A 401/403
push response suggests identity/audience/invoker configuration; repeated 503 suggests processing or dependency failure.
Repair the cause before raising concurrency. Keep the same inbox and report records across restarts. A controlled
rolling restart can restore a terminal subscriber; PDB/draining reduce disruption but do not eliminate duplicate work.
Do not use liveness to restart every service during a shared SQL outage. Use backward-compatible image rollback when
failure follows a release; database rollback, events, and object generations are separate concerns.

## Timeout and retry boundaries

SDK publish retries are bounded to 20 seconds, with a 30-second caller deadline; the normal outbox lease is 90 seconds.
SQL claim/retry/mark operations have five-second transaction bounds. Relay backoff adds jitter and caps at 300 seconds.
GCS adapters use bounded connect/read/retry settings and the processor enforces its streaming size limit. Pub/Sub push
ACK deadlines and Cloud Run request timeouts are in [Cloud Run](cloud-run.md). A platform timeout does not cancel all
application work, and future cancellation does not undo a publish accepted by the broker. Idempotency remains required.
No new automatic retry layer wraps SQL transactions or multiplies SDK/broker retries in this phase.

## Validation

```sh
./mvnw -B -ntp verify
./scripts/terraform/validate.sh
docker compose up -d --build
python3 scripts/local/smoke.py
python3 scripts/local/regression.py
```

Tests cover queue sampling against real PostgreSQL, published-event exclusion, readiness transitions for worker state,
MDC scope cleanup, and existing publish/commit failures, NACK redelivery, concurrent duplicates, invalid documents, and
report-based recovery after input loss. Structured output was parsed to verify all four operation names, success and
failure outcomes, nonnegative numeric duration, service tags, and trace correlation. Terraform tests check all consumer
backlog/DLQ policies, application signals, missing telemetry, and bounded metric labels.

Local emulators cannot validate actual Cloud Monitoring time series, alert delivery, log ingestion, IAM propagation,
Cloud SQL failover, or a real managed outage. Perform these drills in a prepared development project before promoting
alert policies. No cloud resources were changed during this phase.

Validated on 2026-09-22: 107 Maven tests passed without failures/errors/skips; all five Terraform configurations
validated and nine mock scenarios passed; the rebuilt local stack passed the smoke workflow and all three replay/failure
regressions. Repository whitespace checks passed.
