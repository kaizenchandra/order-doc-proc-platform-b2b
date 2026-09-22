# ADR 002 — Local transactions and idempotent delivery

Status: Accepted (implemented baseline). Reviewed: 2026-09-22.

## Context and decision

SQL writes, Pub/Sub publication, and GCS writes do not share a transaction. Commit an order mutation and its outbox row
in one SQL transaction. Claim one leased event at a time, commit the lease, publish outside the transaction, and mark
published only while the claim token and lease remain valid. Retry uses the same domain event ID.

SQL consumers claim the unique `(consumerName, eventId)` inbox key in the same transaction as their business effect.
Only acknowledge after that transaction commits. Each result consumer has a separate subscription. Events carry explicit
versions and routing metadata; unsupported input is rejected for retry/DLQ rather than silently accepted.

## Alternatives and consequences

Direct SQL-then-publish dual writes can lose events between those actions. Publishing before SQL commits can expose
rolled-back state. Holding SQL locks across a remote publish still does not create atomicity and increases contention.
A distributed transaction coordinator would require participation from every resource and is not the selected design.
CDC could replace polling later but adds connector operations and does not eliminate consumer deduplication.

A crash after publish but before the publication marker produces duplicates. Lost acknowledgments do likewise.
At-least-once transport plus idempotent effects is the guarantee; there is no end-to-end exactly-once claim. Invalid
outbox data remains pending with backoff and needs an operator/code fix. No global event ordering is assumed, and the
notification audit is a history of received events rather than an authoritative projection of latest order state.

Revisit polling throughput and lease settings based on measurements. Any replay or purge change must preserve inbox,
canonical report, and event identity retention together.

## Evidence

[OutboxRelay](../../services/order-service/src/main/java/com/synechisveltiosi/platform/order/adapter/messaging/OutboxRelay.java),
[OutboxStore](../../services/order-service/src/main/java/com/synechisveltiosi/platform/order/adapter/persistence/OutboxStore.java),
[ResultReceiver](../../services/order-service/src/main/java/com/synechisveltiosi/platform/order/adapter/messaging/ResultReceiver.java),
and [MessagingIT](../../services/order-service/src/test/java/com/synechisveltiosi/platform/order/MessagingIT.java)
cover publish/commit gaps, lease replacement, concurrency, transaction rollback, and lost ACK redelivery.
