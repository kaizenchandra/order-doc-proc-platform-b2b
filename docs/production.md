# Production engineering

Phase 17 adds a bounded workload harness, isolated logical restore drills, explicit post-restore message retention,
and the acceptance process below. It does not certify production readiness: cloud performance, managed failover/PITR,
alert delivery, identity boundaries, and recovery objectives still require evidence from a prepared environment.

## Repeatable workload measurement

Start the [local environment](local-environment.md), then run:

```sh
mkdir -p target/production
python3 scripts/production/load.py --local --count 20 --concurrency 2 > target/production/load.json
python3 scripts/production/load.py --local --count 2 --concurrency 2 --bytes 26214400 > target/production/load-max-document.json
python3 scripts/production/restore_local.py > target/production/restore.json
```

Each workload creates a unique order, retries its idempotency key, registers and uploads a synthetic PDF-marker file,
confirms it, polls to a terminal state, and checks both the stored checksum and canonical report. It creates durable
test records and objects; it does not delete shared data. Synthetic marker files exercise the implemented metadata
processor, not a full PDF parser. Notification fan-out is verified separately by the existing smoke/regression suite.

The harness defaults to 20 workflows, two workers, 64 KiB documents, a 90-second processing deadline, and 20-second HTTP
timeouts. It caps count at 1,000, workers at 16, document size at 25 MiB, and processing wait at 300 seconds. A synchronous
HTTP call can overrun the processing deadline by its own timeout. Redirects are rejected; the API bearer token is not
attached to signed storage requests. Results contain counts, safe error categories, elapsed time, successful workflow
throughput, and nearest-rank p50/p95/p99 observations. Any failed workflow returns a nonzero exit code. Endpoint and
exception text, tokens, signed URLs, document IDs, and request/response bodies are excluded from reports.

For an **authorized test environment**, obtain a short-lived, tenant-scoped token in a private file under ignored
`target/`, then use:

```sh
python3 scripts/production/load.py \
  --api https://orders.test.example/api/v1/orders \
  --token-file target/test-tenant.token --count 100 --concurrency 4 --bytes 1048576 \
  > target/production/cloud-load.json
```

Use a dedicated test tenant and track its cleanup through the data-retention procedure. Do not pass bearer tokens as
command arguments or commit token files. Remote endpoints require HTTPS; only explicit `--local` mode uses the fixed
loopback API and local issuer. No remote load was executed in this phase.

This is a **closed-loop** workload: slower responses reduce offered load. It measures observed behavior for that run,
not maximum capacity or open-loop overload tolerance. API percentiles include failed calls; terminal latency samples
include successful document results only. Polling adds up to roughly half a second to terminal observations. Small
samples, warm caches, the local issuer, and emulators cannot establish a production SLO.

## Capacity and overload acceptance

Record the release digests, region, infrastructure configuration, runner location, dataset, payload distribution,
concurrency, duration, and observed errors with every result. Keep credentials and customer payloads out of evidence.
In a prepared dev/staging environment:

1. Start with a small warm-up, then measure repeated runs at concurrency 1, 2, 4, and 8. Include typical files and the
   25 MiB boundary. Choose a business-approved sustained arrival rate and run a separate paced/soak test long enough to
   expose queue growth and resource pressure; the supplied finite harness alone does not establish sustained capacity.
2. Observe API latency/errors, CPU/memory/restarts, SQL active/waiting connections and storage growth, outbox age,
   subscription backlog/oldest age, QUEUED age, processing failures, and notification lag throughout each run.
3. Stop increasing load when errors, persistent queue growth, connection waits, or memory pressure appear. Verify that
   queues drain after offered load falls. A 202 response means durable acceptance, not completed processing.
4. Repeat while rolling out a compatible release and during an approved dependency-recovery drill. Confirm no lost
   business effects and no duplicate notification intent per event. Preserve the original event and request IDs.

Current limits are initial configuration, not measured cloud capacity:

| Resource | Configured planning bound | Required observation |
| --- | --- | --- |
| Order GKE pods | HPA 2–4; five SQL connections/pod | CPU scaling, startup/drain time, pool waits |
| Order rollout | `(2 × 4 + 1) × 5 = 45` connections; budget 50 | Old/terminating pod overlap and runtime role limit |
| Notification Cloud Run | Concurrency 5, max 4 instances/revision, pool 5 | Two revisions can use 40 connections; runtime role limit 50 |
| Document Cloud Run | Concurrency 2, max 4 instances/revision | Processing memory, GCS latency, publisher latency |
| Shared SQL | `max_connections=200`; separate runtime logins | Enforce each runtime's 50-connection limit; reserve administration/migrations |

Instance caps and planning formulas are not hard global connection guarantees. Verify runtime role limits as described
in [Terraform setup](terraform.md). Do not raise concurrency or pool sizes independently. Retry backoff and queueing
provide recovery behavior, not tenant fairness or admission control. Per-tenant quotas/rate limiting and sustained
open-loop overload testing remain production acceptance work if required by the traffic contract.

Use the proposed objectives in [operations](operations.md): 99.9% API availability over 30 days and 99% of accepted
documents terminal within five minutes. The availability budget is 43.2 minutes per 30 days. Business and operations
owners must approve the actual SLI denominator, latency target, exclusions, and burn-rate response. Current symptom
alerts do not calculate an SLO or prove it has been met.

## Recovery boundaries and retention

Cloud SQL backup/PITR configuration is in Terraform; recovery must be rehearsed against a **new isolated instance**.
Keep the original instance and evidence intact until validation and explicit cutover approval. Follow the backup mode
actually configured, rather than mixing standard and enhanced backup procedures.
[Cloud SQL restore overview](https://docs.cloud.google.com/sql/docs/postgres/backup-recovery/restore).

All four source subscriptions now explicitly retain acknowledged messages for seven days. This allows controlled
replay when a database restore loses an effect for a message already acknowledged. Merely retaining unacknowledged
messages does not provide this recovery path. The change incurs retained-message storage and must be applied and
verified before relying on it; enabling it cannot resurrect messages already discarded.
[Pub/Sub replay prerequisites](https://docs.cloud.google.com/pubsub/docs/replay-overview).

| State | Current retention/recovery position |
| --- | --- |
| SQL | Seven retained backups; seven-day PITR logs; restored data needs cross-system reconciliation |
| Source subscriptions | Seven days, including acknowledged messages after the Phase 17 configuration is applied |
| DLQ inspection subscriptions | 31 days; resolve failures well before expiry |
| Input/report objects | Versioning enabled; no automatic deletion; generation identity must survive replay |
| Inbox, outbox, idempotency, audit rows | No automatic purge; preserved as recovery/deduplication evidence |
| Release images and SBOMs | GitHub manifest/SBOM artifact 90 days; registry retention must independently preserve rollback digests |

Do not introduce independent TTLs for inbox records or reports. A purge policy must cover the longest authorized replay,
backup-restore, incident-investigation, and client-idempotency windows plus a safety margin. The 31-day DLQ window already
exceeds the source replay window. A 31-day-old DLQ replay cannot rely on a seven-day inbox TTL. Older manual replays need
retained deduplication/canonical state or an explicit reconciliation strategy.

Assign a data owner to approve document/audit retention, deletion requests, legal holds, and cost bounds. An abandoned
upload cleanup design must check expired registration status, absence of queued/referenced work, exact object generation,
and concurrent completion before deleting anything. No purge job or bucket lifecycle deletion is enabled by this phase.
Monitor accumulating versions, unpublished/published outbox rows, inboxes, and audit storage until that policy is approved.

## Managed recovery drill

Before the drill, agree on a recovery point objective (maximum acceptable data loss) and recovery time objective (time
to restore the validated business workflow), designate an incident lead, and choose a test dataset and observation
window. RPO/RTO have not been approved or measured for this platform. Record detection, containment, restore, replay,
validation, and reopening times separately; database restore duration alone is not RTO.

1. In a prepared test project, create identifiable pre- and post-checkpoint orders/documents; record only safe IDs,
   timestamps, release digests, and expected effects in restricted evidence. Verify backups/PITR and the actual retained
   message window before starting. Test operator permissions and private network access.
2. Quiesce ingress and **all writers**, including the order relay/result consumer and both push consumers, through a
   reviewed maintenance change. Account for HPA and in-flight work; merely removing API traffic does not stop writers.
   Preserve subscription/DLQ state and object generations. Do not purge queues or reset acknowledgments casually.
3. Restore to a new SQL instance at the chosen point. Validate both logical databases, migration histories, constraints,
   business records, inbox/outbox state, runtime grants, and connection limits. The production databases share one SQL
   instance, while the local drill uses two independent snapshots and cannot model a common recovery point.
4. Compare SQL state with retained messages and canonical GCS reports. Classify missing registrations, reports whose
   SQL reference was lost, unpublished/re-publishable outbox rows, and acknowledged effects missing from restored inboxes.
   Preserve original event IDs, processingRequestIds, and exact object generations. Do not manufacture IDs or terminal states.
5. Review a bounded replay interval with an overlap margin inside the **actual** retained-message horizon. An authorized
   recovery operator may seek the affected subscriptions under this maintenance procedure after evidence review;
   ordinary incident handling should use the targeted DLQ procedure in [operations](operations.md). Subscription seeks
   affect the entire selected subscription, not just one tenant. Start with a small verified replay cohort where possible.
6. Reapply results for surviving registrations and reconcile notification effects. Requests with retained canonical reports
   can republish their stable results; missing post-restore orders/registrations cannot be reconstructed from those reports
   alone. Account for those records as potential data loss and use business reconciliation, not blind retry. Events after
   the restore point and data beyond retention are explicit recovery limits.
7. Verify the full authenticated upload-to-report path, tenant isolation, notification intent counts, backlog drain, and
   alert routing. Approve cutover to the restored instance with a reviewed configuration/secret change. Record the actual
   data-loss interval and end-to-end recovery time before reopening traffic and closing the drill.

A zonal HA failover drill is separate from a corrupt-data PITR drill. Single-region GCS/Pub/Sub persistence and regional
SQL HA do not establish region-loss recovery. Cross-region backup availability, data-residency approval, object/message
recovery, alternate-region networking, and DNS cutover remain unresolved until a dedicated regional drill demonstrates
them. Do not advertise regional RPO/RTO from the current local or zonal configuration.

## Local restore drill semantics

`restore_local.py` uses PostgreSQL 17 tools in the fixed local Compose database containers. It takes a logical dump of
each source, creates a random `restore_drill_*` database from `template0`, restores with transaction/error checking,
checks populated core tables and validated constraints, and removes only the database it created. It never restores over
`orders` or `notifications`. Run the smoke workflow first so the restored tables have evidence. Backup files live in a
private temporary directory under ignored `target/` and are deleted. Reports contain aggregate row counts only.

Failure cleanup is tested: a failed restore drops its new copy; a failed creation never drops an existing database.
Forced process termination or an unavailable database can prevent cleanup. An operator should inspect leftover
`restore_drill_*` databases and remove only confirmed drill copies. This exercise proves logical dump/restore mechanics
and schema/data restoration; it does not verify Cloud SQL PITR, application reconnection, SQL role backup, GCS recovery,
or consistency between the two separate local snapshots.

## Launch evidence and operating ownership

Use a release-specific review record with an owner, observed result, restricted evidence link, and decision for each item:

- GitHub protections, federation rejection tests, private runner isolation, image scan/SBOM, and rollback rehearsal from
  [CI/CD](cicd.md). Test the approval gate; environment names alone provide no review enforcement.
- Measured cloud workload and recovery drills above, an approved traffic contract and RPO/RTO, and a tested previous
  release compatible with current schema/events. Use additive schema changes; remove old fields only after rollback and
  delayed-message compatibility windows close.
- Tested paging destination, escalation owner, maintenance communication, and incident procedures. Exercise alert delivery
  and missing telemetry, not just Terraform resource creation.
- Verified runtime database limits, private connectivity, workload identity, Secret Manager version/rotation procedure,
  credential revocation, TLS/issuer rotation, and a reviewed break-glass identity.
- Data retention/deletion ownership and cost monitoring for SQL auto-growth/backups, object versions, retained messages,
  logging/metrics, NAT, and idle HA infrastructure. Configure project budgets and billing notifications with finance;
  budget notifications do not enforce a spending cap. Avoid automatic billing shutdown for a stateful production service.
- A scheduled dependency/base-image update and vulnerability response owner. A failed release scan blocks publication;
  exceptions require a documented risk decision and reviewed policy change, not a silently skipped check.

No checklist item is satisfied solely by this document. Unmeasured capacity, cloud DR, and missing external protection
settings remain launch blockers even though this engineering phase is implemented.

## Validation evidence (2026-09-22)

- Local 20-workflow run: 20 succeeded at concurrency 2 with 64 KiB inputs; elapsed 10.81 s, observed successful throughput
  1.85 workflows/s, completion-to-terminal p95 1.075 s. These are a small local sample, not a capacity guarantee.
- Boundary run: both 25 MiB document workflows succeeded, including canonical checksum verification.
- Both local database backups restored with populated core tables and validated constraints; temporary copies removed.
  Orders restored 37 orders, 35 documents, 72 outbox rows and 35 inbox rows; notifications restored 72 rows in each core table.
- Seven production-tool unit tests and 14 CI contract tests passed; actionlint passed with its optional external tools
  disabled locally. Terraform formatting/five configuration validations and ten mocked scenarios passed.
- Verify CI now runs a six-workflow bounded check and both isolated restore drills after the existing application suite,
  and retains aggregate drill reports for seven days. No Java code changed; no cloud apply or remote load was performed.
