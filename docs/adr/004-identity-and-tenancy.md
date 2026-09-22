# ADR 004 — Separate tenant authorization from infrastructure identity

Status: Accepted (implemented baseline). Reviewed: 2026-09-22.

## Context and decision

The order API validates JWT signature, issuer, audience, required claims, and operation scopes. It derives tenant identity
from the validated token and queries/locks resources under that tenant. A resource ID does not grant access. Signed
upload/report capabilities are issued only after authorization, expire quickly, and are not logged.

Use separate runtime, push invocation, GKE node, and storage-signing identities. Cloud Run platform invocation checks
and application JWT checks are distinct boundaries. GKE/Cloud Run/CI use federated or attached workload identities,
not committed service-account keys. API documentation routes remain anonymous at the application layer; anonymous
health is restricted to `/livez` and `/readyz`, with status-only output.

## Alternatives and consequences

A shared all-purpose service account makes a compromised worker a broader platform compromise. Trusting a caller's
supplied tenant or knowing an object path would bypass business authorization. Platform IAM alone does not enforce
order tenancy, while application JWT validation alone does not configure internal Cloud Run ingress.

Bucket grants cover all tenants' objects in that bucket; tenant isolation depends on application checks and generated
paths. The signing account's signBlob capability remains powerful even with narrowly scoped IAM. Signed URLs are bearer
capabilities until expiry. Their create-only/generation semantics and real IAM behavior require cloud acceptance tests.
OpenAPI visibility is intentional current behavior, not evidence that business endpoints are unauthenticated. The GKE
Ingress chart routes `/api/v1/`; direct network access has a different surface and must be reviewed.

Revisit per-tenant infrastructure isolation for contractual/compliance needs, signing compromise risk, and documentation
visibility before exposing new routes or networks. No production security certification is implied by local tests.

## Evidence

[API security](../../services/order-service/src/main/java/com/synechisveltiosi/platform/order/config/ApiSecurity.java),
[security controls](../security.md), [API integration tests](../../services/order-service/src/test/java/com/synechisveltiosi/platform/order/OrderApiIT.java),
and [IAM resources](../../infrastructure/terraform/modules/platform/security.tf).
