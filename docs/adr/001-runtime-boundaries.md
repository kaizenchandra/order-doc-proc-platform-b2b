# ADR 001 — Split runtime by execution model

Status: Accepted (implemented baseline). Reviewed: 2026-09-22.

## Context and decision

Order-service runs the authenticated API, an outbox polling scheduler, and a streaming result subscriber in the same
GKE workload. Document-service and notification-service execute bounded authenticated push requests on Cloud Run.
The Helm chart owns the order workload; Terraform owns the managed infrastructure and Cloud Run services.

The order process needs continuous worker execution as well as HTTP. Document processing is limited to metadata/checksum
work on inputs up to 25 MiB, and notification only records durable intent. Those consumers fit request-scoped execution.

## Alternatives and consequences

Running everything on GKE simplifies the runtime model but keeps request workers provisioned and expands cluster
operations. Running everything on Cloud Run requires a deliberate model for continuous relay/subscriber execution;
HTTP configuration alone would not preserve that lifecycle. Separating the relay into its own GKE deployment permits
independent scaling but adds coordination, identities, and rollout surfaces.

The chosen split introduces two deployment and networking models. API autoscaling also scales relays/subscribers, so
SQL and Pub/Sub pressure must be budgeted together. Worker health contributes to order readiness; a failed result
subscriber can remove an otherwise functional API replica from service. This favors consistent workload health over
partial API availability and needs outage testing. Readiness does not itself restart a worker.

Revisit when measured API and messaging scaling needs diverge, queue draining competes with request latency, or processing
exceeds the push request budget. Long-running OCR/analysis would require a separate execution design.

## Evidence

See [GKE](../gke.md), [Cloud Run](../cloud-run.md), and [production capacity](../production.md).
Implementation: [messaging configuration](../../services/order-service/src/main/java/com/synechisveltiosi/platform/order/config/MessagingCoreConfiguration.java),
[Helm chart](../../infrastructure/helm/order-service/Chart.yaml), and
[Cloud Run resources](../../infrastructure/terraform/modules/platform/cloud-run.tf).
