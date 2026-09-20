# Phase 6 — Document-service

Document-service handles authenticated Pub/Sub push requests without SQL. Its application workflow processes an exact
input generation, creates or reuses a canonical report, publishes the stable result, and only then acknowledges the
HTTP delivery. [Phase 7](cloud-storage.md) adds GCS implementations; disabled storage ports fail closed.

## Request and acknowledgment contract

`POST /internal/pubsub/document-requests` accepts `application/json` with the standard wrapped Pub/Sub body:

```json
{
  "subscription": "projects/PROJECT/subscriptions/document-requests",
  "message": {
    "messageId": "transport-message-id",
    "data": "BASE64_OF_DOCUMENT_PROCESSING_REQUESTED_ENVELOPE",
    "attributes": {"traceparent": "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01"}
  }
}
```

The decoded event uses the shared [version 1 envelope](messaging.md). Only `DocumentProcessingRequested` from
`order-service` is accepted. The input contains document ID, processing request ID, bucket, object name, exact
generation, and processor version. The business envelope, not the transport message ID, determines processing identity.
The subscription must exactly match the configured full resource name. Bucket and processor version are allowlisted.

The controller reads at most 96 KiB plus one byte, including for chunked requests. Decoded event data is capped at
64 KiB. Invalid base64, duplicate JSON fields, trailing values, unknown types/versions, malformed UUIDs, and non-string
generations are rejected. Additive event fields are tolerated. Trace context is scoped to each delivery and propagated
on publication; invalid trace IDs are ignored.

| Response | Meaning |
| --- | --- |
| 204 | Canonical success or terminal-failure report exists and result publication was accepted |
| 401 | Missing or invalid bearer token |
| 403 | Authenticated request targets a disallowed endpoint/method |
| 413 | Push body exceeds the limit |
| 503 | Processing rejected or storage/publication failed; delivery must remain retryable |

No background task continues after sending 204. Malformed events deliberately remain non-2xx; configure subscription
retry and dead-letter policies to handle poison messages. Google's [push delivery documentation](https://docs.cloud.google.com/pubsub/docs/push)
defines success status codes as acknowledgments and other responses as negative acknowledgments.

## Push authentication

The resource server verifies an RS256 JWT signature using configured JWKs, issuer, timestamps, required expiration and
subject, expected audience, service-account email, and verified email claim. This is the push invocation identity,
separate from the document runtime identity that reads GCS and publishes results. Tenant identity comes from the
validated event produced by order-service, not an end-user access token.

All other application paths are denied. Authentication is stateless with no cookie session. Cloud Run invocation IAM
and Pub/Sub token-creation permissions remain deployment work in Phase 12/13; application validation alone does not
provision them. See Google's [authenticated push guidance](https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions).

## Processor version 1

Version `1` performs PDF metadata inspection: `application/pdf` content type, a PDF 1.x/2.x header, a final `%%EOF`
marker after trimming trailing whitespace, and a SHA-256 checksum of the original bytes. It does not parse PDF object
structure, extract text, perform OCR, or scan for malware. Passing these checks is not a guarantee that a PDF is safe
or fully renderable. Future stronger inspection should use a new processor version.

Reads are streamed with an 8 KiB buffer and a 1 KiB trailer window. The processor reads no more than 25 MiB plus one
byte; that extra byte distinguishes an oversized object. Input streams close on both success and failure. `bytesRead`
is the inspected count, not necessarily the full object length for an early rejection.

| Condition | Durable failure code |
| --- | --- |
| Content type is not `application/pdf` | `UNSUPPORTED_FORMAT` |
| Empty PDF input | `EMPTY_DOCUMENT` |
| Input exceeds 25 MiB | `DOCUMENT_TOO_LARGE` |
| Header or trailer check fails | `INVALID_PDF` |

Storage I/O errors, missing generations, permission failures, and publisher errors propagate as retryable delivery
failures. They are never translated into these terminal document outcomes. Unknown processor versions are also rejected
without writing a terminal report, allowing a correctly deployed version to handle redelivery.

## Durable report and retry boundaries

`DocumentObjects.open` must read the exact requested object generation. `ReportStore.find` looks up the canonical path
`{tenantId}/{processingRequestId}/result.json` in the configured report bucket. If absent, the processor computes a
candidate and calls `createIfAbsent`. That method must atomically return either the created object or the concurrent
winner, including its actual generation. A lookup followed by an unconditional overwrite does not satisfy the port.

The version 1 JSON report contains:

- The typed original request envelope and immutable input identity.
- A stable result event UUID and completion timestamp.
- Inspected byte count and exactly one of SHA-256 or terminal failure code.

`CanonicalReportCodec` explicitly encodes and validates this JSON, with a 96 KiB limit. It does not deserialize arbitrary
Java class names. A corrupt or unsupported persisted report fails the delivery rather than being overwritten.

The report cannot contain its own generation before creation. The stable result event is therefore reconstructed from
the stored metadata and the generation returned by GCS. Its correlation ID comes from the winning request; causation ID
is that request's event ID. This preserves the same event on every replay without rewriting the immutable report.

Before publication the workflow checks that the saved tenant, order, document, input object/generation, processing
request, and processor version match the incoming request. Reusing a processing request ID for different input is an
error. A duplicate event for the same processing identity can reuse the existing report even if its envelope ID differs.

| Failure window | Redelivery behavior |
| --- | --- |
| Input read or report creation fails before commit | Retry processing; publish nothing |
| Report write commits but its response is lost | Read the saved report and publish its stable result |
| Concurrent workers compute different candidate event IDs | All publish the winning persisted report's event |
| Publication fails or has an ambiguous timeout | Keep the report; retry the same result event |
| Publication succeeds but HTTP response is lost | Republish the same result; downstream inbox deduplicates |

The Pub/Sub adapter uses ADC/TLS normally, or an explicitly configured plaintext/no-credentials emulator channel.
It waits for broker acceptance, with a 20-second SDK retry budget and 30-second caller deadline. Timeout does not prove
non-delivery. Application shutdown closes the publisher. Phase 7 configures bounded storage RPC retries/timeouts; deployment
must coordinate processing time, push acknowledgment deadline, concurrency, and Cloud Run request timeout.

## Configuration and phase boundary

| Variable | Default / requirement |
| --- | --- |
| `PUSH_AUDIENCE` | Required; expected push token audience |
| `PUSH_SERVICE_ACCOUNT_EMAIL` | Required; expected invocation identity |
| `DOCUMENT_REQUESTS_SUBSCRIPTION` | Required full `projects/.../subscriptions/...` name |
| `PUSH_ISSUER` | `https://accounts.google.com` |
| `PUSH_JWK_SET_URI` | `https://www.googleapis.com/oauth2/v3/certs` |
| `UPLOAD_BUCKET` | Required for processing; input bucket allowlist |
| `REPORT_BUCKET` | Required for processing; canonical reports bucket |
| `MESSAGING_ENABLED` | `false`; enable outbound Pub/Sub explicitly |
| `PUBSUB_PROJECT_ID` | Required when messaging is enabled |
| `DOCUMENT_RESULTS_TOPIC` | `document-results` |
| `PUBSUB_EMULATOR_HOST` | Empty; use `host:port` only for an explicit local emulator |

Missing push identity settings fail startup. Unconfigured storage or publisher ports fail delivery with 503. Supplying
bucket names alone does not enable GCS: also set `STORAGE_ENABLED=true` and provide runtime ADC. There is no production
in-memory storage. Phase 7 SDK contract tests verify generation matching and create-only preconditions on outgoing requests.

## Validation

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -B -ntp -pl services/document-service -am verify
```

Tests cover successful and invalid input, bounded reads, exact-generation arguments, duplicate requests, conflicting
processing identities, concurrent report creation, ambiguous write recovery, publication failure recovery, canonical
JSON round trips, malformed wire data, and HTTP acknowledgment behavior. Real HTTP integration tests use a local JWK
server and signed JWTs to exercise issuer, signature, audience, expiration, and service-account checks.

These tests validate application behavior with storage and publisher substitutes. They do not prove GCS preconditions,
real Pub/Sub/IAM behavior, or Cloud Run invocation. Full reactor verification additionally runs the existing PostgreSQL
integration tests and requires Docker. No SQL dependencies or migrations were added to document-service.

## Interview takeaway

Without a database, durable deduplication can live in an immutable object. Create-only writes select one canonical
outcome; stable event identity makes retries across the storage-to-Pub/Sub boundary safe for downstream consumers.
The HTTP success response belongs after durable storage and accepted publication.
