# Phase 14 — Security boundaries and hardening

This phase adds a total metadata-body limit to the order API, narrows cloud IAM permissions to the operations used by
the adapters, and expands negative JWT tests across all three services. It documents the controls already implemented
and their remaining deployment checks. It is not a penetration-test or dependency-vulnerability certification.

## Threats and controls

| Boundary / threat | Implemented control | Evidence / limitation |
| --- | --- | --- |
| Anonymous or forged API caller | Signature, issuer, audience, expiration, subject, tenant UUID, scope checks; deny unmatched routes | OrderApiIT covers invalid signatures/claims/scopes and cross-tenant access |
| Client selects another tenant's resource | Tenant comes from verified JWT; tenant-scoped queries and database constraints | Cross-tenant order/document/report tests; no client-selected tenant override |
| Oversized metadata / parser memory use | At most 64 KiB for authenticated POST/PATCH, before JSON parsing or business changes | Known-length and chunked overflows return 413; exact boundary accepted; no order/outbox written |
| Forged or misrouted push | Cloud Run IAM plus application JWT signature/issuer/audience/verified-email checks and exact subscription allowlist | Negative JWT/push tests; actual IAM and Google frontend delivery still require cloud acceptance |
| Duplicate/replayed messages | Stable event IDs, transactional inbox, create-only canonical report | Existing concurrency/retry tests; replay is expected, not prevented by JWT alone |
| Object overwrite or tenant path substitution | Server-generated paths, exact generations, create-only preconditions, authorized report lookup | Storage adapter tests and local workflow; fake GCS does not prove IAM or signed-header enforcement |
| Broad runtime cloud permissions | Separate runtime, push, node, and signing identities; resource-scoped grants | Terraform mock assertions; inherited IAM must still be reviewed in the real project |
| Credential disclosure | ADC/Workload Identity, Secret Manager references, no private keys or password payloads in Terraform | Deployment workflows must also protect state, logs, secret delivery, and CI credentials |
| SQL exposure / privilege escalation | Private connector route, separate databases/users, external migration identity | Terraform provisions connectivity; operator prepares and verifies SQL grants before activation |
| Container compromise | Non-root images, restricted GKE pods, read-only GKE root, bounded tmp, denied capabilities | Existing runtime tests; Cloud Run has a writable ephemeral filesystem |

The request limiter runs after Spring Security authorization and before MVC dispatch. It reads at most 65,537 bytes,
including bytes after a valid JSON object, then either rejects or replays the bounded body to the blocking controller.
Unauthenticated requests remain 401 even when oversized. The 64-KiB bound applies to JSON metadata, not document bytes;
PDF bytes go directly to GCS. A byte bound is not a slow-client deadline or rate limiter. Edge request/concurrency/rate
policies and measured capacity remain part of later resilience/production phases.

JWT regression cases now explicitly reject unsigned (`alg=none`) and HS256 tokens, missing `exp`/`sub`, and `nbf` well
in the future. Existing tests reject wrong signing keys, issuer/audience, expired tokens, missing tenant/scope, and wrong
push identity. These tests use local ephemeral RSA keys, never production credentials. TLS termination, issuer key
rotation, and actual managed push tokens must also be checked in the deployed environment.

## Least-privilege cloud contract

| Principal | Capability | Resource |
| --- | --- | --- |
| Order runtime | Publish | order-events, document-requests topics |
| Order runtime | Subscribe | order-document-results subscription |
| Order runtime | `storage.objects.get` | Upload bucket only |
| Order runtime | `iam.serviceAccounts.signBlob` | Dedicated storage signing account only |
| Storage signer | `storage.objects.create` / `storage.objects.get` | Upload bucket / report bucket respectively |
| Document runtime | `storage.objects.get` | Upload and report buckets |
| Document runtime | `storage.objects.create` | Report bucket only |
| Document runtime | Publish | document-results topic |
| Order / notification runtime | Cloud SQL Client | Project connector authorization; SQL grants remain database-specific |
| Notification runtime | Secret accessor | Its database-password secret only |
| Document / notification push | Cloud Run Invoker | Its respective receiving service only |
| Pub/Sub service agent | OIDC ID-token creation | Each push account only |
| Pub/Sub service agent | Subscribe / publish | Each source subscription / corresponding dead-letter topic |
| GKE node | Default node-service role and registry read | Project node operations / image repository |

Terraform defines three project custom roles containing only `iam.serviceAccounts.signBlob`, `storage.objects.get`,
and `storage.objects.create`, respectively, and binds them on the relevant service account or bucket. Get-only access
removes bucket enumeration; create-only grants omit deletion/overwrite and administration. Google's object permissions
separate these operations; replacing an existing object requires delete as well as create.
[Cloud Storage IAM permissions](https://docs.cloud.google.com/storage/docs/access-control/iam-permissions)

The Pub/Sub agent now receives `roles/iam.serviceAccountOpenIdTokenCreator`, rather than the broader general Token
Creator role. Authenticated push requires `iam.serviceAccounts.getOpenIdToken`; it does not need access-token generation
or arbitrary signing APIs. [Push authentication permissions](https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions),
[service-account roles](https://docs.cloud.google.com/iam/docs/service-account-permissions)

The order application uses its source ADC credentials to call signBlob for the dedicated signer. The custom signing
role removes unrelated IAM API permissions but signing is still powerful: arbitrary signatures can enable credential
construction. Treat the signer as a capability available to a compromised order runtime, keep its storage grants narrow,
and never give it administrative roles. This is not tenant-level IAM isolation: application authorization remains the
tenant boundary. [signBlob authorization](https://docs.cloud.google.com/iam/docs/reference/credentials/rest/v1/projects.serviceAccounts/signBlob)

Custom roles require deployment permission to create/update roles. The narrowed grants have passed schema and mock
validation, not a live Google IAM test. Before production, exercise upload signing/PUT, exact-generation read, report
create/read/download, and authenticated push under the actual identities. Verify that listing/deletion, foreign-secret
reads, and cross-service invocation fail. Inspect inherited roles and organization policies; resource-level member
bindings do not revoke pre-existing broad project grants.

## Credentials, rotation, and disclosure prevention

- Use short-lived ADC/Workload Identity credentials. Prevent service-account key creation/upload through the applicable
  organization policy and review existing keys using the platform administration workflow; this repository does not
  change organization-wide policy.
- Keep runtime SQL roles distinct from schema owners. Revoke public cross-database connectivity and elevated default
  Cloud SQL user roles; use the preparation checks in [Terraform](terraform.md). Runtime migrations remain disabled.
- Rotate database passwords through the secret workflow, publish a numeric secret version, and roll out matching
  runtime credentials. Environment-injected credentials require a revision/pod restart. A single login's password
  change can interrupt reconnects during overlap: use a coordinated maintenance/rotation procedure rather than claiming
  atomic rotation across SQL, Secret Manager, and Kubernetes.
- Rotate issuer signing keys with overlapping JWKS availability for still-valid tokens; verify new-key acceptance and
  retired-key rejection after the configured token/cache lifetime. For an incident, remove affected invoker grants or
  stop message delivery through the operational workflow, then revoke/rotate the compromised credentials. Do not delete
  inbox or report records to force replay.
- Do not log Authorization headers, JWTs, signed URLs, passwords, PDF bytes, or full message bodies. Existing application
  error responses expose fixed messages; notification records retain metadata rather than customer/storage payloads.
  Audit proxy/APM/CI logging separately, especially request headers, SDK debug logging, SQL exception details, and plan
  artifacts. Local support tokens/credentials are development-only and must not be deployed.

Signed URLs are temporary bearer capabilities. Return them only after tenant authorization, preserve `no-store`, and
require callers to send the signed headers exactly. Upload size/generation constraints and real expiry enforcement
must be verified with GCS; local fake behavior is not security evidence. The PDF marker/checksum processor is not
malware scanning, content sanitization, or a full PDF validator; downstream consumers must treat uploaded content as
untrusted. External email/SMS delivery remains outside this application.

## Verification and remaining work

```sh
./mvnw -B -ntp verify
./scripts/terraform/validate.sh
```

On 2026-09-22, the complete Maven reactor passed 104 tests with no failures, errors, or skips. All five Terraform
configurations passed validation; eight mock scenarios passed, including the least-privilege permission assertions.
No cloud configuration was applied. Live IAM tests, SQL privilege verification, managed JWT delivery, secret rotation,
edge abuse controls, artifact/dependency scanning, and production monitoring remain explicit deployment/release checks.
CI scan automation is Phase 16; operational alerts and resilience are Phase 15. Do not interpret this phase's local
checks as proof that a production environment is secure.

Phase 18 verified the probe contract: only `/livez` and `/readyz` expose anonymous status; Actuator routes are denied. Swagger/OpenAPI routes remain anonymous at the application layer and require a launch visibility review; see [AR-05](architecture-review.md#findings-and-disposition).
