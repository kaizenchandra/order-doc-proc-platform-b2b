# notification-service

Phase 8 adds authenticated `POST /internal/pubsub/events` consumption for version 1 order-created,
order-status-changed, document-processed, and document-processing-failed events. Each event commits its inbox claim,
audit record, and `IN_APP` / `RECORDED` notification intent together. Duplicate event IDs acknowledge without repeating
those effects. No email or SMS delivery is performed.

Supply `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`, plus `PUSH_AUDIENCE`, `PUSH_SERVICE_ACCOUNT_EMAIL`, and
`NOTIFICATION_EVENTS_SUBSCRIPTION` (comma-separated full subscription resource names, without spaces).
Use independent notification subscriptions for order-events and document-results; both push to this endpoint.
Google issuer and public signing keys are configured by default; overrides support isolated authentication tests.

Flyway migrates at startup by default. Production deployments will migrate separately and set
`DB_MIGRATIONS_ENABLED=false`. Hibernate validates the schema and Open Session in View is disabled.

Run `./mvnw -B -ntp -pl services/notification-service -am verify` with JDK 21 and Docker.
See [consumer behavior and validation](../../docs/notification-processing.md).
