# Phase 7 — Cloud Storage

Order-service now signs bounded upload authorizations, verifies stored upload metadata, and authorizes report downloads.
Document-service reads exact object generations and creates immutable canonical reports. Both use the BOM-managed
Google Cloud Storage Java SDK. No buckets, IAM policies, or cloud resources are created by the application.

## Enabling the adapters

| Setting                       | Service | Purpose                                                       |
|-------------------------------|---------|---------------------------------------------------------------|
| `STORAGE_ENABLED=true`        | Both    | Enable GCS; default is false                                  |
| `UPLOAD_BUCKET`               | Both    | Allowlisted input bucket                                      |
| `REPORT_BUCKET`               | Both    | Allowlisted canonical report bucket; must differ from uploads |
| `GCS_SIGNING_SERVICE_ACCOUNT` | Order   | Service account used by IAM signing                           |

Runtime credentials use Application Default Credentials. The intended deployed sources are workload identities, not
private-key files. Order-service constructs `ImpersonatedCredentials` for the explicit signing identity and invokes
IAM signing through the SDK. Its GCS metadata client still uses the runtime identity. Storage and Pub/Sub enablement
are independent; document processing needs both enabled for successful delivery.

When disabled, existing fallback ports return 503. Enabling storage requires nonempty, distinct bucket settings;
order-service additionally requires the signer identity. The applications never install an in-memory production fallback
or silently redirect to a local emulator. Phase 9 adds explicit `local` profile adapters; see the
[local environment guide](local-environment.md).

HTTP storage clients configure a 3-second connection timeout, 10-second read timeout, and at most three attempts within
a 20-second SDK retry budget. These are per-operation limits, not a deadline for the entire multi-operation workflow.
Streaming may involve multiple RPCs. Deployment must coordinate Cloud Run concurrency and request/push deadlines with
processing limits and storage latency. IAM credential/signing calls have their own SDK behavior.

## Upload authorization

Registration allocates a server-owned object name and commits before signing. The returned V4 signed URL authorizes
PUT to exactly that object. It carries these signed request headers:

```text
Content-Type: <registered content type>
x-goog-if-generation-match: 0
x-goog-content-length-range: 1,26214400
```

Clients must send every returned header unchanged. GCS's XML API defines generation-match zero as create-only and
the content-length range as an inclusive PUT size restriction.
See [GCS XML headers](https://docs.cloud.google.com/storage/docs/xml-api/reference-headers).
Content-Type is explicitly included in the V4 extension headers, which the SDK uses for canonical signing.

The authorization never extends the original ten-minute registration window. The adapter caps its lifetime, leaves a
one-second margin, and checks the SDK-generated timestamp/expiration before returning the URL. Near-expired windows
are rejected. Signing failures return a sanitized 503; retrying the same idempotency key recovers the registration.
Signed URLs are neither persisted in SQL nor logged. Authorization responses use `Cache-Control: no-store`.

Configure the storage signer without permission to delete existing uploads. Create-only authorization prevents
replacement of a
live object; bucket retention/lifecycle policy must also avoid deleting an upload while its authorization is valid.
Do not rely on a filename, client-provided size, or declared content type as evidence of valid bytes.

## Completion and processing reads

`/complete` obtains GCS metadata for the allocated object. Missing uploads return 409, invalid sizes return 422,
inconsistent metadata returns 502, and provider failures return a sanitized 503. The verified record contains the
server-returned generation and size. The SQL workflow then rechecks state under its existing lock and persists those
values with the processing request outbox event.

The document adapter supplies the requested generation on both metadata and media requests, also setting a matching
generation precondition on media reads. It never substitutes the current/latest object for a missing generation.
Raw streams prevent transparent gzip decompression from changing the bytes that are hashed. The processor owns
and closes the returned stream and retains its 25 MiB bound. A 64 KiB SDK chunk setting bounds read buffering.

An unavailable generation is retryable delivery failure, not a fabricated terminal document result. Preserve uploaded
generations for the supported processing and replay window; replacing/deleting them makes old requests unprocessable.

## Canonical report storage

Reports use `REPORT_BUCKET/{tenantId}/{processingRequestId}/result.json`. Creation validates the path against the
report's request and sends `doesNotExist()` (generation match zero). JSON reports have `application/json` and `no-store`
metadata. The returned GCS generation is combined with saved report metadata to reconstruct the stable result event.

On a 412 conflict the adapter loads the winning report; it never overwrites it or publishes the losing candidate.
For lookup, it obtains metadata and reads that exact generation. An absent metadata lookup means no report. A failure
reading a pinned generation, corrupt JSON, wrong canonical path, or a report exceeding 96 KiB is an error. Metadata
size checks and bounded stream reads both protect report parsing.

Other write errors propagate, including ambiguous failures after an accepted write. Redelivery first performs lookup,
so it can recover the canonical report without creating a new event identity. This is the application of GCS
[request preconditions](https://docs.cloud.google.com/storage/docs/request-preconditions) at the storage-to-messaging
boundary.

## Report downloads

`GET /api/v1/orders/{orderId}/documents/{documentId}/report` checks `orders:read`, tenant ownership, and terminal
document
state before calling storage. Both PROCESSED and FAILED documents have reports. Pending documents return 409 and
foreign-tenant documents return 404. Signing runs outside the SQL transaction.

The response has `url`, `method: GET`, empty `headers`, and `expiresAt`. Its V4 signature covers the recorded generation
and response parameters for an `application/json` attachment. Lifetime is at most five minutes. It does not authorize
whatever future content might exist at the same name. The signing identity requires read access to the report bucket;
creating a signed URL does not itself prove the object is still retained. V4 query parameters are signed, as described
in
the [Java signing options](https://docs.cloud.google.com/java/docs/reference/google-cloud-storage/latest/com.google.cloud.storage.Storage.SignUrlOption).

## Deployment responsibilities

| Identity                | Required capabilities                                                                |
|-------------------------|--------------------------------------------------------------------------------------|
| Order runtime           | Read upload metadata; invoke `iam.serviceAccounts.signBlob` on the designated signer |
| Signing service account | Create upload objects; read report objects                                           |
| Document runtime        | Read input generations; create and read canonical reports; publish result events     |

Grant capabilities on the specific buckets/topics and signer resource. Keep report deletion/overwrite permissions out
of runtime roles. Enable the IAM Service Account Credentials API for remote signing. The Pub/Sub push invocation
identity remains distinct from these runtime identities. Terraform/IAM provisioning is Phase 13.

Buckets should use uniform bucket-level access and public access prevention. For browser clients, configure CORS for
the actual application origins, PUT/GET methods, and required upload headers; verify preflight and response behavior
in the deployment environment. Do not make buckets public to work around CORS. Lifecycle policies must preserve
canonical reports, input generations, and inbox entries for compatible retry/replay windows. Orphan cleanup and
retention enforcement remain operational work; this phase adds no automatic deletion jobs.

## Validation and limits

Run `./mvnw -B -ntp verify` with JDK 21 and Docker. Full reactor verification passed on 2026-09-21: 81 tests, no
failures
or skips. The new coverage includes:

- Real SDK V4 canonical-request hashing, signed upload bounds, expiration, and report generation query parameters.
- Metadata inspection, missing/oversized uploads, bucket restrictions, and sanitized provider errors.
- Actual SDK HTTP requests to scripted local GCS JSON API responses: exact-generation reads, raw gzip bytes,
  generation-match-zero creation, 412 winner recovery, missing pinned reads, malformed/oversized reports, and failures.
- Authenticated HTTP report downloads for both successful and failed documents, tenant isolation, read scope,
  no-store responses, and signing outside SQL transactions.

Local HTTP fixtures validate adapter requests and recovery behavior, not the cloud service's enforcement. Before
deployment, use a dedicated test bucket and IAM identity to verify real signed PUT success, duplicate-upload 412,
oversize rejection, header tampering rejection, expiration, browser CORS, generation-pinned download, and concurrent
create-only report writes. No live cloud resources were accessed or credentials required by this test suite.

## Interview takeaway

A signed URL is a narrowly scoped bearer capability, not an upload record. Persist the verified object generation,
pin every later read, and use a storage precondition to select one durable report. Client retries remain safe because
the report and result identity survive the gap between GCS and Pub/Sub.
