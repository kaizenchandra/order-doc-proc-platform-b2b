# Phase 4 — Order-service workflows and HTTP API

## Implemented scope

The service now exposes the six planned order/document endpoints. Application services own SQL transactions; controllers handle validated request/response records. Every business event is written to the outbox in the same transaction as its business change. Publication arrives in Phase 5.

The storage boundary is `DocumentStorage`: upload authorization and inspection run outside SQL transactions. A real GCS adapter arrives in Phase 7. The default adapter returns 503; the HTTP integration tests supply an in-memory metadata adapter and never claim to upload real bytes.

## Startup and identity

Use JDK 21 and configure:

| Environment variable | Meaning |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Order PostgreSQL database credentials |
| `AUTH_ISSUER` | Exact trusted JWT issuer |
| `AUTH_JWK_SET_URI` | Trusted provider's public JWK set endpoint |
| `AUTH_AUDIENCE` | Required token audience |
| `UPLOAD_BUCKET` | Server-controlled upload bucket; required for document registration |
| `DB_POOL_SIZE` | Optional connection pool limit, default 10 |

Web startup fails when identity configuration is missing. Production must configure trusted HTTPS identity endpoints; tests use a local JWK server. The decoder verifies RS256 signatures, issuer, audience, and validity timestamps. Tokens must also contain an expiration, a nonblank subject, and a canonical UUID `tenant_id` claim.

GET requests require `orders:read`; POST and PATCH require `orders:write`. The authenticated tenant is used in every resource lookup. Unknown resources and resources belonging to another tenant return the same 404 response. Customer IDs identify customers within that tenant; a separate customer directory/ownership integration is not implemented.

The API uses stateless bearer authentication with no session or cookie login. Issuer and JWK configuration follow [Spring Security's JWT resource-server configuration](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html). Deployment identity, broader authorization policies, and operational security remain later-phase work.

## HTTP contract

All paths below start with `/api/v1/orders`. Responses set `Cache-Control: no-store` and `X-Correlation-ID`. Clients may supply a UUID `X-Correlation-ID`; otherwise the service generates one and records it on new outbox events.

| Method and path | Request | Success |
|---|---|---|
| `POST /` | Create-order JSON and `Idempotency-Key` | 201 order snapshot; `Location` points to the order |
| `GET /{orderId}` | No body | 200 current order |
| `PATCH /{orderId}/status` | Target `status` and `expectedVersion` | 200 updated order |
| `POST /{orderId}/documents` | Filename/content type and `Idempotency-Key` | 201 document plus upload authorization; `Location` points to the document |
| `GET /{orderId}/documents/{documentId}` | No body | 200 document status/result metadata |
| `POST /{orderId}/documents/{documentId}/complete` | No body | 202 document with the current processing request |

The create endpoint is exactly `/api/v1/orders` (without a trailing slash).

### Create an order

```http
POST /api/v1/orders
Authorization: Bearer <access-token>
Content-Type: application/json
Idempotency-Key: order-2026-001

{
  "customerId": "358ac132-79c5-4445-9320-82be283f05c1",
  "customerReference": "PO-2026-001",
  "totalAmount": 49.95,
  "currency": "INR"
}
```

The response contains `id`, `customerId`, `customerReference`, `totalAmount`, `currency`, `status`, `createdAt`, `updatedAt`, and `version`. New orders have status CREATED and version 0. Amounts must be positive, fit 17 integer digits plus 2 fractional digits, and use USD/EUR/GBP/INR. No rounding is performed.

Order creation atomically writes the order, `OrderCreated` event, and idempotency response. Keys allow 1–128 ASCII letters, digits, dots, underscores, colons, or hyphens. They are scoped to tenant and operation. A replay returns the original response snapshot and Location even if the order has since changed; different input returns 409. Amounts are normalized to two decimal places before hashing. Property order and insignificant JSON whitespace do not affect the request fingerprint.

PostgreSQL transaction-scoped advisory locks serialize simultaneous requests for an absent key across replicas. Full-key uniqueness remains the durable constraint. Rollback releases the lock and leaves the key reusable. Records carry a 24-hour expiration, but no cleanup job exists yet: replay protection continues while a record is retained. A future cleanup policy must explicitly define the reuse window.

### Change status

```json
{"status": "CONFIRMED", "expectedVersion": 0}
```

Use the `version` returned by GET. A stale version or forbidden transition returns 409. Repeating the current state with the current version succeeds without another event. Status changes take an order lock and atomically write `OrderStatusChanged`.

Allowed transitions: CREATED → CONFIRMED → FULFILLED; CREATED or CONFIRMED → CANCELLED. Terminal orders cannot be reopened. Cancellation does not revoke an already issued upload URL or cancel previously queued processing.

### Register and complete a document

```json
{"fileName": "invoice.pdf", "contentType": "application/pdf"}
```

The server allocates a document UUID and an object name under `{tenantId}/{orderId}/{documentId}`. Clients cannot select storage paths. Registrations have a ten-minute upload window and a 25 MiB maximum verified size.

A successful registration response has this shape:

```json
{
  "document": {
    "id": "<document-uuid>",
    "orderId": "<order-uuid>",
    "status": "AWAITING_UPLOAD",
    "uploadExpiresAt": "<timestamp>"
  },
  "upload": {
    "url": "<short-lived-upload-url>",
    "method": "PUT",
    "headers": {"<required-header>": "<value>"},
    "expiresAt": "<timestamp>"
  }
}
```

The example omits other document fields. The adapter defines the actual upload method and required headers. Its contract requires authorization for only that object, create-only semantics, and the supplied expiration/size bounds. Phase 7 must validate those behaviors against GCS; the current default returns 503.

Registration commits before signing. If signing fails, retry with the same key to recover the same registration without another row. Signed URLs are generated on demand and are never stored in idempotency records. A replay after queueing returns current document state with `upload: null`. An expired registration returns 409; create a new registration/key to start a new upload window. Cleanup of abandoned registrations is future work.

After uploading, call `/complete`. The service reads actual storage metadata through the adapter; it does not trust client-provided bucket, object name, generation, or size. It then takes the parent order lock, rechecks current document/order state, and commits QUEUED plus `DocumentProcessingRequested` atomically. The event contains the exact object generation and a stable processing request UUID.

Concurrent or repeated completion returns the same processing attempt without emitting another request. A completed or failed attempt is also returned unchanged; `/complete` is not a reprocessing endpoint. Missing uploads, expired registrations, and terminal orders reject new queueing. Storage calls do not hold a database transaction open.

Document responses include identifiers, filename/content type, status, upload expiry, processing request ID, actual size, checksum, failure code, completion timestamp, and version. Processing results and report download authorization arrive in later phases.

## Errors

Expected errors use `application/problem+json` with `type`, `title`, `status`, and `detail`. Framework validation may add `instance`. SQL text, token contents, and stack traces are excluded from error bodies.

| Status | Meaning |
|---|---|
| 400 | Malformed JSON, invalid fields, missing/invalid idempotency key, invalid correlation UUID |
| 401 | Missing or invalid bearer token; includes `WWW-Authenticate: Bearer` |
| 403 | Token lacks the required scope |
| 404 | Resource absent from the authenticated tenant/parent |
| 409 | Idempotency input conflict, stale version, invalid state, missing upload, or database constraint conflict |
| 502 | Storage adapter returned metadata for a different object |
| 503 | Storage unavailable/unconfigured, or a handled database availability/temporary failure |

Clients should retry ambiguous network failures with the original idempotency key. A 409 stale-version response requires a fresh read and a business decision rather than blindly overwriting current state.

## Validation

On 2026-09-16, `clean verify` passed across all six Maven reactor entries on JDK 21.0.12.1 with PostgreSQL 17.6 Testcontainers and OrbStack. All 31 tests passed with no skips: 5 domain tests, 10 order persistence tests, 11 HTTP integration tests, and 5 notification persistence tests.

The HTTP suite starts real Tomcat, uses Java's HTTP client and signed RSA JWTs, serves a local public JWK set, and exercises real PostgreSQL transactions. It covers signature/issuer/audience/expiry/tenant/scope rejection, tenant and parent isolation, validation errors, original-response replay, concurrent creation, stale status updates, duplicate completion, signing/inspection failures, expiry/cancellation, and rollback/retry after injected outbox constraint failures. It also asserts that storage calls run outside SQL transactions. GCS signing/upload and Pub/Sub delivery are not exercised in this phase.

## Key Points

Controllers do not mutate entities directly. Transactional workflows coordinate domain changes with durable events. Storage inspection is separated from the final SQL transaction, which revalidates state under a lock.

## Production Considerations

The API is ready for the next implementation phases, not cloud deployment. GCS, Pub/Sub publication, customer directory integration, rate limits, identity-provider provisioning, migrations with separate privileges, retention/recovery jobs, and deployment configuration remain outstanding in their designated phases. Restrict the supplied identity endpoints and bucket through deployment configuration.

## Interview Takeaway

A retry-safe HTTP operation needs a durable request identity, input fingerprint, concurrency control, and an atomic response/business commit. Database transactions cannot make an external upload atomic; verify storage, then recheck local state before committing the processing request.
