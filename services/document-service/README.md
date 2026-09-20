# document-service

Phase 6 implements an authenticated Pub/Sub push endpoint, bounded PDF metadata processing, canonical report JSON,
and stable result publication. The service has no SQL dependency.

Target: Cloud Run.

`POST /internal/pubsub/document-requests` accepts wrapped Pub/Sub messages. It returns 204 only after the canonical
report is durable and Pub/Sub accepts the result. Read/write/publication failures return non-success responses for
redelivery. Duplicate requests reuse the winning report and event ID.

Required startup settings: `PUSH_AUDIENCE`, `PUSH_SERVICE_ACCOUNT_EMAIL`, and the full
`DOCUMENT_REQUESTS_SUBSCRIPTION` resource name. Google issuer/JWK defaults are provided; local tests use signed tokens
and a local JWK endpoint. Outbound Pub/Sub uses `MESSAGING_ENABLED=true`, `PUBSUB_PROJECT_ID`, and optionally
`DOCUMENT_RESULTS_TOPIC` (default `document-results`).

Phase 7 adds GCS adapters. Set `STORAGE_ENABLED=true` with distinct `UPLOAD_BUCKET` and `REPORT_BUCKET` values and
runtime ADC to enable exact-generation reads and create-only canonical reports. Storage defaults to disabled and
returns 503 until configured. No production in-memory fallback is installed. See the [Cloud Storage guide](../../docs/cloud-storage.md).

Run `./mvnw -B -ntp -pl services/document-service -am verify` from the repository root with JDK 21. Tests use local
HTTP/JWK servers, publisher substitutes, and scripted GCS HTTP responses exercised through the real SDK;
they need no GCP credentials or database.

See [processing, security, and recovery design](../../docs/document-processing.md).
