# Phase 3 — Domain and database

## Implemented scope

Order and notification persistence now use PostgreSQL, Flyway migrations, and JPA. The document service remains free of
SQL dependencies. HTTP application services, event publication, cloud adapters, and deployment resources belong to later
phases.

## Domain rules

`Order.create` assigns the initial `CREATED` state and validates a positive, exact `numeric(19,2)` amount. Supported
currencies are USD, EUR, GBP, and INR; amounts with excess fractional digits are rejected rather than rounded. Tenant,
customer, and creation fields are immutable through the public domain API.

Order transitions are `CREATED → CONFIRMED → FULFILLED`, with cancellation allowed from CREATED or CONFIRMED. Repeating
the current state returns false; terminal states cannot be reopened. Callers should emit an event only when a transition
returns true.

Documents start in AWAITING_UPLOAD. Queueing requires a verified object matching the registered bucket/name, a positive
immutable generation, a size up to 25 MiB, and an unexpired registration. A processing result is accepted only for the
current QUEUED request. FAILED documents may be retried with a new request ID; retry clears the previous result. Late
and duplicate results return false. Unused registrations can expire when their upload window closes.

Transition timestamps cannot precede the current state. Callers should supply the local transition time; an event's
occurrence time is separate metadata. `VerifiedUpload` represents storage-adapter evidence, not an HTTP request body.

## Data ownership

| Database     | Tables                                                  | Purpose                                                         |
|--------------|---------------------------------------------------------|-----------------------------------------------------------------|
| Order        | `orders`, `order_documents`                             | Tenant-owned business state                                     |
| Order        | `outbox_events`                                         | Durable events plus fields for future relay leases/retries      |
| Order        | `inbox_events`                                          | Consumer/event deduplication                                    |
| Order        | `api_idempotency`                                       | Tenant/operation/key uniqueness and stored successful responses |
| Notification | `inbox_events`, `audit_records`, `notification_records` | Atomic event consumption, audit history, and recorded intent    |

Each service has its own V1 migration and must connect to a separate database/user. A composite document foreign key
`(tenant_id, order_id)` prevents cross-tenant parent references. Repositories expose tenant-scoped lookups; these are
not a replacement for authenticated tenant resolution in Phase 4.

Foreign keys remain inside a service database. Outbox and inbox retention are independent of aggregate and audit
retention. Notification status is `RECORDED`, with channel `IN_APP`; it does not establish external delivery.

## Persistence and transaction design

- Flyway owns DDL. Hibernate uses `ddl-auto: validate`; open-in-view and SQL initializer scripts are disabled.
- Orders and documents use nullable `Long` JPA versions. New assigned-UUID entities persist correctly, and stale writes
  fail optimistic locking. An explicit pessimistic order lookup is available for operations that need serialization
  inside a transaction.
- Documents use scalar parent IDs to avoid implicit loading of an unbounded object graph. Collection reads return paged
  slices.
- Journal append and inbox claim methods require an existing transaction (`MANDATORY`). Application services must wrap
  the business mutation, outbox/idempotency writes, or inbox/audit effects in one transaction.
- Inbox claims use `INSERT … ON CONFLICT DO NOTHING` against `(consumer_name, event_id)`. A rolled-back claim can be
  retried; a committed duplicate does not reapply the effect.
- Journal inserts use `persist`, so reused primary keys fail rather than overwrite committed records. JSON payloads are
  mapped to PostgreSQL JSONB objects and constrained by the schema.
- Database constraints enforce valid row shapes, uniqueness, and references. Domain methods enforce transition history;
  direct SQL updates do not run those methods.

Example future application transaction:

```text
BEGIN
  claim inbox (consumer, event)
  if already claimed: finish without applying effects
  apply current-request domain transition or append audit + notification
COMMIT
acknowledge message
```

Acknowledgment and publication are outside the SQL transaction. The relay and consumer workflows will be implemented in
their designated phases.

## Configuration and validation

Order and notification startup require `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`. `DB_POOL_SIZE` defaults to 10 and 5
respectively. `DB_MIGRATIONS_ENABLED` defaults to true for this milestone. Tests supply temporary PostgreSQL credentials
directly and require Docker; no cloud credentials are needed.

Run the complete build on JDK 21:

```sh
./mvnw -B -ntp clean verify
```

On macOS with OrbStack, if Testcontainers cannot discover the socket:

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 21)" \
DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" \
./mvnw -B -ntp clean verify
```

The tests cover exact money, terminal transitions, upload ownership/expiry, stale processing results,
migration/Hibernate compatibility, JSONB representation, tenant foreign keys, optimistic locking, atomic
outbox/idempotency writes, inbox races and rollback, and notification deduplication/transaction failures. JUnit 5 tests
start Spring explicitly, preserving the lifecycle approach documented in Phase 2.

Validation on 2026-09-16: `clean verify` passed across all six reactor entries using JDK 21.0.12.1, Maven 3.9.16, and
PostgreSQL 17.6 Testcontainers on OrbStack. All 20 tests passed (5 domain, 10 order persistence, 5 notification
persistence), with no failures, errors, or skips. Cloud behavior and HTTP workflows are outside this validation.

## Key Points

Domain methods protect transitions; database constraints protect persisted structure. An inbox claim and its business
effect must share one commit. Storing an outbox event prepares reliable publication but does not publish it.

## Production Considerations

Production must use a separate migration identity and a runtime role without DDL privileges, size connection pools
across all replicas, and define retention/replay policies before cleanup jobs are enabled. Cloud SQL connectivity,
secrets, operational recovery, and infrastructure enforcement remain later-phase work. V1 migrations establish the
initial schema; subsequent deployed schema changes require new migrations.

## Interview Takeaway

Explain which invariant belongs in a domain method, which belongs in a database constraint, and which requires a shared
transaction. Optimistic locking protects concurrent business edits; inbox uniqueness protects duplicate message
processing.
