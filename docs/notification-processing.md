# Phase 8 — notification-service

The notification service now accepts wrapped Pub/Sub JSON at `POST /internal/pubsub/events`.
Supported version 1 contracts are `OrderCreated`, `OrderStatusChanged`, `DocumentProcessed`, and
`DocumentProcessingFailed`. The shared envelope validates producer identity and payload consistency.
Unknown versions/types, malformed JSON, duplicate JSON keys, invalid payloads, and unexpected subscriptions fail closed.
Order amounts are decoded with decimal precision preserved.

## Authentication and configuration

JWT verification checks signature, issuer, expiry, audience, subject, exact push service-account email, and verified
email.
All other application routes are denied. Authentication is stateless. Required settings:

| Setting                                | Purpose                                                 |
|----------------------------------------|---------------------------------------------------------|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Notification-owned PostgreSQL database                  |
| `PUSH_AUDIENCE`                        | Exact configured push token audience                    |
| `PUSH_SERVICE_ACCOUNT_EMAIL`           | Allowed Pub/Sub invocation identity                     |
| `NOTIFICATION_EVENTS_SUBSCRIPTION`     | Comma-separated full subscription names, without spaces |
| `PUSH_ISSUER`                          | Defaults to `https://accounts.google.com`               |
| `PUSH_JWK_SET_URI`                     | Defaults to Google's public OAuth signing keys          |

Create independent notification subscriptions on order-events and document-results, both targeting this endpoint.
Do not reuse the order-service result subscription. Cloud Run IAM invocation policy and subscription resources remain
in the deployment phases. A body subscription field is an additional configuration check; token verification and
publisher/invoker IAM establish trust.

## Commit and recovery behavior

`notification-domain` is the stable consumer identity. The transaction claims `(consumer_name, event_id)` using
PostgreSQL `ON CONFLICT DO NOTHING`, then inserts one audit and one notification record. Concurrent duplicates wait
for the competing transaction and produce one committed effect. The HTTP controller returns 204 only after the
transactional service returns, including transaction commit. Commit failures return 503 and remain retryable.

The wrapper is limited to 96 KiB, including chunked requests; decoded events are limited to 64 KiB. Oversized wrappers
return 413. Other decoding or processing failures return 503, allowing Pub/Sub retry/dead-letter policy to handle them.
Invalid authentication returns 401. Poison messages are never acknowledged as processed.

Audit summaries retain order status transitions or document/request IDs, processor version, and processing outcome.
They omit customer details and storage locations. Envelope tenant, aggregate, correlation, type, version, and occurrence
time are retained separately. Trace context is scoped to handling; failure logs contain only exception class names.
Notification status is `RECORDED`, not evidence of external delivery. Events are audited independently of arrival order;
this consumer does not reconstruct order state or decide whether a document result is current.

Inbox retention must cover the replay window. Audit uniqueness also retains consumer/event identity; deleting inbox
rows alone does not permit replay of already audited events. No cleanup or replay endpoint is added in this phase.

## Validation

Codec unit tests cover all four contracts, exact decimal values, unsupported versions/types, invalid transitions and
sources, trailing JSON, duplicate keys, size limits, subscription allowlisting, and invalid Base64.
New PostgreSQL/HTTP integration tests cover signed JWT claim/signature rejection, concurrent duplicate delivery,
malformed/oversized requests, and database commit failure followed by successful redelivery. Existing persistence tests
cover transaction requirements, rollback, schema ownership, and uniqueness constraints.

Validation completed during Phase 9 on 2026-09-21: codec tests and both PostgreSQL/HTTP integration suites pass
with Docker and local HTTP access. The earlier environment blockers are resolved. Rerun with:

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -B -ntp -pl services/notification-service -am verify
```

Next phase: Local Environment. No deployment resources or external notification providers are introduced here.
