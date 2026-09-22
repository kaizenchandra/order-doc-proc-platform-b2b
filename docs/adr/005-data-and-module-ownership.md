# ADR 005 — Own data by service and share only contracts/telemetry

Status: Accepted (implemented baseline). Reviewed: 2026-09-22.

## Context and decision

Order and notification own separate logical databases and runtime users on one PostgreSQL instance. Order owns orders,
documents, outbox and result inbox; notification owns inbox, audit and IN_APP intent. Document processing has no SQL
adapter. Services communicate through explicit versioned events rather than cross-service queries or shared entities.

The Maven reactor builds three deployable services and two ordinary shared jars: event-contracts and
common-observability. Shared event types have no framework dependency; common observability depends on SLF4J. Service
modules do not import another service's production packages or executable artifact.

## Alternatives and consequences

One shared schema permits convenient joins but couples migrations, permissions, and ownership. A separate SQL instance
per service improves failure isolation at higher baseline cost. A shared domain/entity jar would couple persistence and
business changes across deployments. A modular monolith would reduce deployment complexity and remains a reasonable
alternative if independent runtime/scaling requirements cease to justify three services.

Logical separation does not remove shared instance CPU, connection, storage, maintenance, and failure contention. Runtime
role limits/grants are operator prerequisites. No claim of strict domain-layer independence is made: order domain objects
use persistence annotations, and application workflows refer to concrete repositories. Ports are used at selected I/O
boundaries rather than mechanically wrapping every class.

A shared event jar also creates compatibility obligations: producers and consumers can run different releases. Preserve
old event versions/fields through the rollback and delayed-delivery windows; an atomic reactor build is not an atomic
production upgrade. Static architecture checks cover direct POM dependencies and production source references, not
reflection, every transitive dependency, runtime permissions, or deployed network calls.

## Evidence

[Boundary contracts](../../scripts/ci/test_architecture.py), [module build decisions](../repository.md),
[database design](../domain-database.md), and [connection budgets](../production.md).
