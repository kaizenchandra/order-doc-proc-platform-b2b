# order-service

Phases 3–5 implemented: tenant-scoped persistence and state machines, authenticated HTTP workflows,
transactional outbox writes, leased Pub/Sub publication, and idempotent document-result consumption.

Messaging defaults to disabled. Enable it with `MESSAGING_ENABLED=true`, `PUBSUB_PROJECT_ID`, and `REPORT_BUCKET`;
the topics and result subscription must already exist. Cloud connections use Application Default Credentials.
Set `PUBSUB_EMULATOR_HOST` only for a local emulator. See the [messaging guide](../../docs/messaging.md) for
configuration, delivery guarantees, and recovery.

Phase 7 adds GCS upload signing, metadata inspection, and tenant-checked report downloads. Enable with
`STORAGE_ENABLED=true`, distinct `UPLOAD_BUCKET` / `REPORT_BUCKET`, and `GCS_SIGNING_SERVICE_ACCOUNT`.
The runtime uses ADC; signing uses IAM impersonation without loading a private key. See the
[Cloud Storage guide](../../docs/cloud-storage.md) for permissions, required upload headers, and generation guarantees.

## Database configuration

Supply `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. Flyway migrates on startup by default; production deployments will run
migrations separately and set `DB_MIGRATIONS_ENABLED=false`. Hibernate validates the schema and Open Session in View is
disabled.

Tests use a disposable PostgreSQL container and require Docker during `verify`. The service owns only its database; no
credentials or local DB defaults are embedded.

See [domain and database design](../../docs/domain-and-database.md)
and [HTTP API configuration](../../docs/order-api.md).
