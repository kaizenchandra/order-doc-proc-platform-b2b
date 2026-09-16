# order-service

Phase 3 persistence implemented: Order/document state machines, tenant-scoped repositories, Flyway schema, outbox/idempotency persistence, and transactional inbox claims.

No HTTP controllers or messaging consumers exist yet. Domain use cases arrive in Phase 4.

## Database configuration

Supply `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. Flyway migrates on startup by default; production deployments will run migrations separately and set `DB_MIGRATIONS_ENABLED=false`. Hibernate validates the schema and Open Session in View is disabled.

Tests use a disposable PostgreSQL container and require Docker during `verify`. The service owns only its database; no credentials or local DB defaults are embedded.

See [domain and database design](../../docs/domain-and-database.md).
