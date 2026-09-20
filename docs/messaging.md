# Phase 5 — Pub/Sub and reliable messaging

Order-service now has versioned event contracts, a SQL outbox relay, a Pub/Sub transport, and a transactional
document-result consumer. Messaging is disabled by default. [Phase 6](document-processing.md) adds document processing
and [Phase 7](cloud-storage.md) adds GCS adapters. Notification delivery and infrastructure provisioning remain later work.

## Topics and subscriptions

| Topic | Event types | Consumer subscription |
| --- | --- | --- |
| `order-events` | `OrderCreated`, `OrderStatusChanged` | Separate notification subscription in Phase 8 |
| `document-requests` | `DocumentProcessingRequested` | Document-service push subscription in Phase 6/12 |
| `document-results` | `DocumentProcessed`, `DocumentProcessingFailed` | `order-document-results` streaming pull; separate notification subscription later |

Independent consumers require separate subscriptions to receive their own copy. Order-service publishes to the
first two topics and consumes the result subscription. It does not provision topics, subscriptions, IAM, or
dead-letter policies. No ordering key or global delivery order is assumed.

## Wire contract

`shared/event-contracts` contains framework-independent Java records. Every envelope carries `eventId`,
`eventType`, `eventVersion`, `tenantId`, `aggregateId` (the order ID), `correlationId`, optional `causationId`,
`occurredAt`, `source`, and typed `data`. Version 1 is the only supported version. Event IDs identify events;
`processingRequestId` identifies a particular processing attempt and changes on authorized reprocessing.

`EventCodec` accepts additive fields but rejects unknown event types/versions, mismatched sources or payloads,
duplicate JSON keys, trailing JSON values, invalid required fields, and messages over 64 KiB. Object generations
are positive decimal strings, preserving their precision across JSON clients. Success results have a SHA-256
checksum; failure results have a bounded failure code, with exactly one outcome required.

The relay adds `eventId` and `correlationId` Pub/Sub attributes and a valid W3C v00 `traceparent` when available.
The envelope is authoritative for business identifiers. Trace context is restored after each received message;
span creation/export remains in Phase 15. Producer identity in JSON is contract validation, not authentication:
topic IAM must restrict which runtime identities can publish.

## Outbox publication and recovery

An API mutation and its outbox row commit in one SQL transaction. The relay then:

1. Claims one eligible row in a short, independent transaction using `FOR UPDATE SKIP LOCKED`.
2. Persists a fresh claim token, lease deadline, and incremented attempt count.
3. Publishes outside the SQL transaction and waits for broker acceptance.
4. Marks the row published only if its token still owns an unexpired lease.

Rows are claimed individually so a waiting batch cannot consume its own leases. Concurrent replicas can claim
different rows. The poll processes up to 20 rows, then waits one second. Default leases last 90 seconds; the
publisher bounds SDK retry time to 20 seconds and the caller waits at most 30 seconds.

Failed publication retains the row with jittered exponential backoff, capped at 300 seconds. Invalid event data
is retained with `INVALID_EVENT`; transport failures use `PUBLISH_FAILED`. No row is silently discarded or
automatically deleted after a retry limit. Interrupted publication stops that poll and retains the event.

| Failure boundary | Recovery |
| --- | --- |
| Before the business transaction commits | Neither business change nor outbox event commits |
| After claim, before publication | Lease expires and another worker can claim the row |
| After broker acceptance, before SQL marking | Retry republishes the same envelope and event ID |
| Old worker finishes after lease replacement | Token check prevents it marking or rescheduling the new claim |
| Invalid stored event | Row remains visible for diagnosis and repair; other eligible rows can progress |

This provides at-least-once publication. A timeout can have an ambiguous broker outcome, so consumers must
deduplicate even when a publication attempt appears to fail.

## Result transaction and acknowledgments

The result handler validates the configured report bucket and canonical object name
`{tenantId}/{processingRequestId}/result.json`. Within one transaction it claims the unique
`(order-results, eventId)` inbox entry, locks the tenant-scoped order, loads the document, checks processing
identity/version, and applies the result. The document mutation and inbox claim commit together.

ACK occurs only after the transactional proxy returns successfully. Lost ACKs cause redelivery, which finds
the inbox entry and makes no additional business change. Results for old processing requests and already
terminal documents are recorded as stale and acknowledged without overwriting current state. Wrong tenant,
report location, or processor version causes rejection; a transaction failure rolls back the inbox claim.
Malformed messages and failed transactions are NACKed for subscription retry/dead-letter handling.

The subscriber uses one streaming pull connection, four executor threads, and flow-control limits of 20
outstanding messages and 4 MiB. Application shutdown stops the relay and subscriber before closing publishers.

## Configuration

Database and HTTP authentication configuration from [order-api.md](order-api.md) still applies.

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `MESSAGING_ENABLED` | `false` | Start relay, publishers, and subscriber |
| `PUBSUB_PROJECT_ID` | empty | Required when messaging is enabled |
| `REPORT_BUCKET` | empty | Required when enabled; allowlisted result bucket |
| `PUBSUB_EMULATOR_HOST` | empty | Explicit local `host:port`; enables plaintext and no credentials |
| `ORDER_EVENTS_TOPIC` | `order-events` | Physical order-events topic ID |
| `DOCUMENT_REQUESTS_TOPIC` | `document-requests` | Physical processing-request topic ID |
| `ORDER_RESULTS_SUBSCRIPTION` | `order-document-results` | Existing subscription to document-results |

Spring properties `app.messaging.lease-seconds` (60–600, default 90) and `app.messaging.max-per-poll` (1–100,
default 20) tune the relay. Physical topic IDs must differ; stored logical destinations remain stable when
physical names change. Normal cloud transport uses TLS and Application Default Credentials. The runtime identity
needs publish access to the two outbound topics and consume access to the result subscription.

## Dead letters, retention, and replay

Provision exponential subscription retry and a dead-letter topic with its own retained subscription before
production use. Grant the Pub/Sub service agent publishing access to that topic and subscriber access to the
source subscription. Dead-letter delivery attempt counts are approximate, not a strict application retry bound.
See Google's [dead-letter documentation](https://docs.cloud.google.com/pubsub/docs/dead-letter-topics) and
[retry policy documentation](https://docs.cloud.google.com/pubsub/docs/subscription-retry-policy).

Dead-letter infrastructure is deferred to Terraform in Phase 13. Until configured, NACKs alone do not establish a
bounded poison-message policy. Alerting and dashboards arrive in Phase 15. Operational signals should include
oldest unpublished outbox age, retry counts, subscription backlog age, dead-letter count, and aged QUEUED documents.

For replay, diagnose and repair the cause, inspect the original payload inside the Pub/Sub dead-letter wrapper,
and republish that original envelope through a controlled process. Preserve its event ID and processing request
ID; allocating a new ID defeats event deduplication. Replay into the source topic can redeliver to other
subscriptions too. Reprocessing is a separate authorized business operation that allocates a new processing
request ID. Never change tenants or report paths as a shortcut to accepting a rejected result.

Retain inbox entries and canonical reports for at least the supported retention/replay window. This phase adds
no automatic cleanup or replay endpoint. Inspect pending publication without reading event payloads:

```sql
SELECT event_id, event_type, attempt_count, available_at, lease_until, last_error_code
FROM outbox_events
WHERE published_at IS NULL
ORDER BY occurred_at, event_id;
```

## Validation

Run `./mvnw -B -ntp verify` with JDK 21 and Docker. On an OrbStack installation where Testcontainers does not
discover the socket automatically, scope `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock` to that command.
Tests use disposable PostgreSQL containers and require no GCP credentials.

`MessagingIT` covers concurrent claims, expired-lease fencing, publication outside SQL transactions, retry backoff,
crash recovery with a stable envelope, invalid outbox retention, interrupted publication, ACK after commit,
lost-ACK redelivery, concurrent duplicate results, transaction rollback, stale results, malformed-result NACKs,
processor-version mismatch, and tenant/report isolation. Contract/codec tests cover wire validation and tracing.

The full reactor verification passed on 2026-09-21 with PostgreSQL 17.6 through OrbStack. The transport boundary
in messaging tests uses an in-process publisher substitute; this does not validate real Pub/Sub delivery, IAM,
dead-letter forwarding, or emulator connectivity. Local emulator setup and cloud deployment validation remain
in their designated phases.

## Interview takeaway

There is no atomic transaction across PostgreSQL and Pub/Sub. Durable intent plus stable event identity makes
publication retries safe; an inbox transaction makes repeated delivery safe for the local database while its
deduplication record is retained. ACK belongs after commit, and a publication timeout never proves non-delivery.
