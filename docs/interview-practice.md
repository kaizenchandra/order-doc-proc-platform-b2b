# Mock interview and practice plan

Companion to the [interview guide](interview-preparation.md). Attempt each prompt aloud before reading the answer guide.
Score the explanation, not whether you remember the exact Java class name. State assumptions and ask for requirements
when the problem changes; do not invent traffic, compliance, or recovery targets.

## A 45-minute mock interview

| Minutes | Prompt | What the interviewer should listen for |
| --- | --- | --- |
| 0–5 | Explain the problem and draw the system | Business flow, ownership, explicit completion, actual processor scope |
| 5–10 | Walk through creation and publication | SQL transaction boundary, outbox lease, publish/mark gap |
| 10–15 | Kill the processor after report creation | Durable canonical result and stable result identity |
| 15–20 | Two clients retry while two workers deliver duplicates | Different API/event/attempt identities and atomic inbox effects |
| 20–25 | Increase traffic tenfold | Measurement plan, queue growth, pool/rollout budget, no invented capacity |
| 25–30 | Restore SQL to an earlier point | Quiescing writers, retained ACKed messages, reconciliation and loss limits |
| 30–35 | Compromise a runtime or replay a signed URL | Trust boundary, grant scope, token/capability limits, cloud proof |
| 35–40 | Deploy a new event version and then roll back | Mixed-version compatibility and non-atomic deployment |
| 40–45 | Challenge two design choices and name unfinished work | Alternatives, change triggers, honest evidence and feature boundaries |

## Scenario cards

For each card, answer four things: what is durable, what can be repeated, what identity prevents a wrong effect, and
what observation or test would verify recovery. Do not start by saying “restart everything.”

1. **Lost order response.** The API commits and the client times out. The client retries the same key but changes the amount.
2. **Stale relay worker.** Worker A publishes slowly. Its lease expires, B claims and publishes, then A tries to mark the row.
3. **Report exists, broker unavailable.** The document worker stored its report but could not publish. Before redelivery,
   the original input generation disappears.
4. **Concurrent document deliveries.** Two workers inspect the same request and generate different candidate result UUIDs.
5. **Late result.** Attempt A's result arrives after a newer attempt B has been queued through a future authorized workflow.
6. **Notification outage.** The order API reports PROCESSED, but no notification intent appears.
7. **Missing registration after restore.** A retained result references a document created after the SQL recovery point.
8. **Healthy HTTP, dead subscriber.** The API can serve GET requests, but its continuous result subscriber stops.
9. **More instances, slower service.** During a rollout, pool acquisition times and QUEUED age rise despite low API CPU.
10. **Partially successful deployment.** Cloud Run updated successfully; Helm then failed and rolled back.

## Answer guide

### 1. Lost order response

The order, outbox row, and idempotent response committed together. Same key with different canonical input must conflict;
it must not create another order or silently return a response for a different request. Retrying the original input can
retrieve the saved result. Check `IdempotentRequests` and API idempotency tests. Distinguish replay protection from the
expiry metadata: no automated purge currently removes the saved response.

### 2. Stale relay worker

Publication may occur twice. A's old token/expired lease cannot mark or reschedule B's claim. The stable event ID and
consumer inbox protect the business effect. A lease is a claim fence for SQL updates, not proof that a remote publication
was cancelled. Explain `OutboxStore.published()` and the replaced-lease tests in `MessagingIT`.

### 3. Report exists, broker unavailable

The canonical report contains the original request and stable result. Redelivery finds it before opening the input and
republishes it. No new event identity or reprocessing is required. Verify the stored report matches the request and that
publication succeeds before ACK. If both input and report were lost, this recovery path would not apply.

### 4. Concurrent document deliveries

Both workers may perform computation. Create-only storage selects one canonical report; the loser reads/reuses the
winner. Both publish the winner's result identity. This suppresses duplicate downstream effects; it does not promise
single execution of CPU work. The processor/storage concurrency tests are the evidence to show.

### 5. Late result

The order handler validates the report location and identifies the current document attempt. A mismatched request ID
cannot overwrite B. A valid stale event can be recorded in the inbox without changing document state. Retain the newer
attempt's state. Clarify that the scenario assumes a future application reprocessing workflow: only its domain transition
and stale-result safeguards exist today.

### 6. Notification outage

Inspect the independent notification result subscription, authentication failures, DLQ, and notification SQL transaction.
The order consumer's success does not prove notification success or external delivery. Repair and replay with the original
event ID; do not consume or reset the order subscription to fix the notification subscriber. Check inbox, audit and intent
together under an authorized operational identity.

### 7. Missing registration after restore

Retaining the result does not recreate the lost order/document registration. Repeatedly NACKing or inventing a new ID
cannot repair that fact. Classify it during reconciliation, preserve evidence and canonical objects, and use an approved
business recovery decision. An inbox insert that rolls back with a missing-resource failure is not a committed effect.
State the possible data-loss boundary; do not promise replay can restore all history.

### 8. Healthy HTTP, dead subscriber

Order readiness includes messaging worker health when enabled. A terminal subscriber can make the pod unready even if
HTTP still responds; readiness alone does not restart it. This is a deliberate availability tradeoff in the combined
workload. Inspect the worker/dependency failure and perform controlled recovery. Separating API and workers is an option
if independent availability/scaling is required. Do not add SQL dependency checks to liveness reflexively.

### 9. More instances, slower service

Inspect active sessions, role limits, pool waits, transaction/lock duration, revision overlap, and worker throughput.
Scaling can multiply pools and shared SQL pressure; low API CPU does not prove spare database capacity. Use the configured
rollout budget and measured workload to reduce pressure or adjust the bottleneck. Increasing every pool is not a diagnosis.

### 10. Partially successful deployment

The system now has a mixed application release. Helm rollback does not undo the prior Terraform Cloud Run update. Inspect
actual revisions and state, verify compatibility, and create a fresh reviewed deployment to converge on a chosen retained
release. Do not silently replan after approval or roll back a database schema without a data-compatibility plan.

## Design-change exercises

| New requirement | A strong response starts with | Consequences to address |
| --- | --- | --- |
| Add email delivery | Durable intent is not delivery; model a separate delivery workflow | Provider idempotency, send/record ambiguity, retries, rate limits and delivery status |
| Process 2 GiB documents with OCR | The current 25 MiB/request-bounded metadata model is insufficient | Resumable upload, orchestration, durable progress, cancellation, worker execution budget and canonical output |
| Isolate one large tenant | Define whether isolation means authorization, quotas, data residency, or failure isolation | Admission control versus dedicated resources, migration, cost and operational ownership |
| Support region-loss recovery | Define approved RPO/RTO and residency constraints first | Backup/object/message availability, alternate networking, reconciliation, DNS and measured cutover |
| Automatically remove expired uploads | Expiry rejection is not cleanup and the domain method has no scheduler | Completion race, exact generation, referenced/queued work, audit, legal holds and retention |
| Add a new event version | Producers and consumers can run different releases | Compatibility window, decoder behavior, queued old events, gradual rollout and rollback |

These are discussion exercises, not implemented capabilities. A strong answer names the new commit/failure boundaries
before selecting another managed service or adding a retry loop.

## Scoring rubric

Score each dimension from 0 to 2: 0 = incorrect or missing; 1 = substantially correct but vague on evidence/limits;
2 = concrete mechanism, failure case, and tradeoff explained clearly. This is a practice aid, not a hiring prediction.

| Dimension | Two-point answer |
| --- | --- |
| Business and ownership | Defines actual metadata/intent scope and names each service's data |
| Transactions | Identifies SQL, object, and broker commits separately |
| Identity and duplicates | Distinguishes tenant, API key, eventId, and processingRequestId |
| Failure recovery | Explains at least two crash windows without losing or fabricating state |
| Security | Separates tenant authorization, workload IAM, and signed bearer capabilities |
| Capacity | Uses connection/rollout budgets and measures rather than inventing throughput |
| Deployment | Explains digest promotion, real approval prerequisites, and partial rollback |
| Restore and retention | Explains acknowledged-message loss, canonical state, and replay limits |
| Evidence | Connects a claim to code/test/run evidence and names what it cannot prove |
| Communication | Gives the main decision first, then an alternative and a condition for changing it |

Before considering a rehearsal complete, correct every unsupported claim of exactly-once execution, managed DR proof,
production scale, automatic cleanup, exported tracing, or external notification delivery regardless of total score.
Revisit any dimension scored zero and repeat the affected scenario without notes.

## Three practice sessions

1. **Architecture and code, 30 minutes.** Deliver the one-minute pitch twice, redraw the flow, and explain the seven code
   tour stops. End by distinguishing the four identities: tenant, idempotency key, event ID, processing request ID.
2. **Failures and evidence, 45 minutes.** Answer five scenario cards without notes. Open the relevant test only after the
   explanation. Practice the local demo in advance and prepare a code/evidence fallback if Docker is unavailable.
3. **Full mock, 45 minutes plus review.** Follow the timed mock, score all ten dimensions, and record three weak answers
   to repeat. Close with two unresolved launch gates and two design change triggers, without apologizing or overclaiming.

A useful final answer to “what would you do next?” is to prioritize the documented cloud acceptance gates and one
business-approved missing capability. Do not claim a new implementation phase or cloud deployment has already occurred.
