# Phase 18 — Architecture review

Reviewed: 2026-09-22. Scope: the repository implementation through Phase 17, its tests, deployment definitions, and
operating procedures. The design is coherent for a bounded asynchronous document-metadata platform. Production launch
is conditional on the unresolved acceptance evidence below; this review is not a penetration test or cloud certification.

The [six ADRs](adr/README.md) record the runtime split, transaction boundaries, canonical results, identity/tenancy,
data/module ownership, and release/recovery decisions. They preserve the existing baseline and distinguish implementation
facts from deployment prerequisites. The [Phase 19 interview guide](interview-preparation.md) uses this review as its evidence base.

## Invariants traced to implementation

| Invariant | Implementation reviewed | Evidence and limit |
| --- | --- | --- |
| Tenant identity comes from validated JWT; IDs alone confer no access | `ApiSecurity`, tenant-qualified repository reads and order locks | `OrderApiIT`; real issuer/JWKS rotation remains a cloud check |
| A committed order mutation has a durable event | `OrderWorkflows`, `IdempotentRequests`, SQL outbox in the same transaction | API/persistence integration tests; no SQL/Pub/Sub atomicity claim |
| Publish does not hold a SQL transaction | `OutboxStore` commits a short lease; `OutboxRelay` publishes then marks with token/expiry checks | `MessagingIT` tests publish/commit gap and replaced lease |
| Duplicate delivery cannot repeat a committed SQL effect | Inbox claim and effect share a transaction; result ACK follows transactional proxy return | Messaging and notification persistence tests, including concurrent duplicates |
| Processing is tied to an immutable input identity | Completion pins object generation; document worker reads it and verifies bounded content | Storage/processor tests; emulator does not prove real signed-header enforcement |
| Every retry of an attempt converges on the same canonical result | `DocumentProcessor` reads first, creates if absent, checks original request, republishes stable event | Processor/GCS tests and local recovery regression |
| A late result cannot replace a newer attempt or terminal result | Request ID/version checks and domain transition guards | `MessagingIT` stale-result coverage; no public reprocessing workflow yet |
| Notification consumption is independent | Separate order-event/result subscriptions and notification database | Local smoke checks both durable intents; intent is not external delivery |
| Release approval applies to a concrete artifact/plan | Verified run manifest, digest images, target binding, saved-plan hash | CI contract tests; GitHub environment protection is external setup |
| Restore does not equate to recovered business consistency | Retained ACKed messages plus canonical/inbox state and reviewed reconciliation | Terraform recovery contract and local logical restore drill; managed PITR remains untested |

## Commit and failure boundaries

| Failure window | Durable state and recovery |
| --- | --- |
| Order transaction rolls back before commit | Neither order mutation nor its outbox event is committed; retry the API idempotency key |
| API commit succeeds but response is lost | Stored idempotent response/resource identity survives; same key/input replays, changed input conflicts |
| Registration commits but URL signing fails | Registration remains; retry the same registration key within its original upload window to issue authorization |
| Upload succeeds but completion is never called | Object exists with an awaiting registration; no processing event exists; expiry/cleanup is not automatic |
| Completion inspects storage, then races another completion | Final SQL transaction rechecks state under the order lock; one attempt/outbox event wins |
| Event publishes but publication marker fails | Lease expires; publication repeats with the same domain event ID |
| Worker reads bytes but crashes before report creation | No canonical result yet; retry reads the exact input generation again |
| Report persists but result publication/ACK fails | Retry loads the stored report and republishes the stable result; input need not still exist |
| Result SQL transaction fails | Inbox/effect roll back together and delivery is not acknowledged |
| SQL commit succeeds but ACK fails | Duplicate delivery encounters the inbox and does not repeat the effect |
| SQL is restored behind broker acknowledgments | Reviewed replay and object/registration reconciliation are required; see production recovery limits |

These guarantees depend on retaining the identity records and canonical objects. Duplicate suppression is not an
unconditional promise after operator deletion, incompatible migrations, or replay beyond the preserved history.

## Findings and disposition

| ID | Finding | Disposition / completion criterion |
| --- | --- | --- |
| AR-01 | All three security configurations allowed anonymous `/actuator/health/**`, contrary to the existing tests and documented probe contract | **Fixed:** removed that allow rule; `/livez` and `/readyz` remain anonymous and status-only. Existing Maven probe tests pass. Swagger/OpenAPI allow rules preserved |
| AR-02 | Baseline implied abandoned registrations automatically become EXPIRED | **Documentation corrected; feature deferred.** Time checks reject late registration retry/completion, but no scheduler invokes `expire()`. Add a concurrency-safe expiry/cleanup workflow and tests only after retention policy approval |
| AR-03 | Baseline mentioned authorized reprocessing as if an application operation existed | **Documentation corrected; feature deferred.** Domain transition and stale-result tests exist; no API/admin workflow invokes it. A future workflow needs authorization, new attempt ID, atomic outbox event, audit and replay tests |
| AR-04 | Local test evidence cannot establish production protections, capacity, or disaster recovery | **Launch gate open:** platform/operations roles must supply the release-specific evidence in `production.md`; no cloud resources were exercised here |
| AR-05 | Swagger/OpenAPI routes are anonymous inside each application | **Recorded current behavior.** Review schemas and direct-network reachability before launch; GKE API ingress routing and Cloud Run IAM are separate controls. If policy requires private docs, change configuration and test it explicitly |
| AR-06 | Trace metadata can be mistaken for exported distributed tracing | **Recorded limitation:** MDC/event correlation exists; no span exporter or measured end-to-end tracing SLO is implemented |
| AR-07 | Module ownership was documented but not checked in CI | **Fixed:** four architecture contracts now check service/shared direct dependencies, cross-service production references, and absence of SQL adapters in document-service |

Deferred features do not weaken existing retry invariants, but must not be advertised as available operations. New
expiry/reprocessing endpoints, deletion jobs, or public route changes are not smuggled into a review phase. The owner
roles above are responsibilities to assign at launch, not claims that a named operator has accepted them.

## Tradeoffs retained and triggers for change

- **Coupled API/relay/subscriber scaling:** keep while measured contention is acceptable; split workers if API demand and
  backlog pressure require independent scaling or readiness behavior.
- **Shared SQL instance:** retain logical database/user ownership and enforce connection limits; separate instances if
  failure isolation, tenant requirements, or measured contention justifies their operational cost.
- **Strict canonical report identity:** keep immutable attempt results; design a new orchestration model for OCR,
  multi-stage processing, mutable corrections, or work exceeding push time limits.
- **Versioned shared contracts:** preserve compatibility across rolling releases and delayed messages. A reactor build
  does not guarantee independently deployed consumers can decode a new event version.
- **Single-region operation:** accept as the documented deployment scope. Do not promise region-loss recovery without
  alternate-region data/network recovery and a measured drill.
- **Sequential Cloud Run/Helm rollout:** keep explicit partial-failure recovery. A coordinated rollback still requires
  compatible data/event schemas; Helm `--atomic` covers only its own release.

## Validation and remaining proof

After the health-route fix, the complete Maven reactor passed 107 tests with zero failures, errors, or skips. Existing
tests exercise status-only probes and denied Actuator routes in all three services, transactional failures, duplicate
handling, tenant checks, storage behavior, and event decoding. The four new architecture contracts pass and are picked
up by the existing CI unittest discovery. These checks examine direct dependencies and production source references;
they are not whole-program or runtime isolation analysis.

The Phase 17 load/restore evidence remains a prior local result, not a newly executed cloud benchmark. The review did
not apply Terraform, dispatch GitHub workflows, or change cloud resources. Launch still requires real IAM/ingress and
signed URL tests, configured approval gates/runners, image scanning, paging verification, measured load/soak behavior,
and managed failover/PITR/reconciliation evidence. See [production acceptance](production.md), [security](security.md),
[CI/CD](cicd.md), and [operations](operations.md).
