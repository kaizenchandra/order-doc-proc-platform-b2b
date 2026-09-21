# Phase 10 — Testing and recovery verification

Tests are organized around business invariants and commit boundaries. Unit tests cover pure behavior; integration tests
use real PostgreSQL transactions, signed JWTs, HTTP servers, and a Pub/Sub emulator. A separate local regression runner
exercises all three packaged services together. No cloud credentials are required.

## Commands

| Command                               | Scope                                                                     | Prerequisites                                   |
|---------------------------------------|---------------------------------------------------------------------------|-------------------------------------------------|
| `./mvnw -B -ntp test`                 | Unit and codec tests                                                      | JDK 21; Maven dependency access on first use    |
| `./mvnw -B -ntp verify`               | Unit plus integration tests                                               | JDK 21, Docker, local socket access             |
| `./scripts/local/test.sh`             | Reactor verification, image rebuild/start, smoke and workflow regressions | Above, Docker Compose, Python 3                 |
| `python3 scripts/local/regression.py` | Workflow regressions only                                                 | An already-running, current local Compose stack |

On macOS, prefix the command with `JAVA_HOME="$(/usr/libexec/java_home -v 21)"` if needed.
Surefire selects `*Test`/`*Tests`; Failsafe selects `*IT`/`*ITCase`. Missing Docker fails integration tests rather than
silently skipping them. Testcontainers creates disposable resources with random host ports and unique database
credentials;
the tests do not connect to the Compose databases. The Pub/Sub transport test uses the same pinned emulator image as
local development, with its own container and isolated project.

A focused verification example:

```sh
./mvnw -B -ntp -pl services/order-service -am -Dit.test=MessagingIT -Dfailsafe.failIfNoSpecifiedTests=false verify
```

Reports are under each module's `target/surefire-reports` and `target/failsafe-reports`. Python workflow regressions
report individual unittest results and exit nonzero on failure. The shell runner stops at the first failing layer.

## Coverage by failure boundary

| Boundary                | Evidence                                                                                                                                                |
|-------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| Domain rules            | Allowed/rejected transitions, stale processing attempts, tenant ownership, bounded document reads                                                       |
| API → SQL               | Real HTTP/JWT verification; idempotent and concurrent creation; optimistic versions; rollback after injected outbox constraints                         |
| SQL → Pub/Sub           | Independent leases, disjoint claims, timeout/crash replay, expired-token rejection, bounded retry, invalid-event retention                              |
| Pub/Sub → SQL           | Real emulator publisher/subscriber wiring, NACK on failed commit, redelivery without republishing, one committed result, independent subscriber fan-out |
| Document input → report | Exact-generation SDK reads, raw compressed bytes, bounded input, mid-stream and close failures, no false terminal result on storage errors              |
| Report → publication    | Create-only winner selection, ambiguous-write recovery, publish failure recovery, stable result identity on concurrent/repeated processing              |
| Push → notification SQL | JWT rejection, chunked size bounds, malformed/unsupported events, concurrent event dedup, rollback/retry, four event types and out-of-order arrival     |
| Trace propagation       | Invalid/zero identifiers, nested scope restoration after exceptions, no cross-thread or reused-worker leakage                                           |
| Local full workflow     | Success/checksum/report, terminal invalid-PDF failure, exact maximum amount, report-based recovery after deleting the test's original upload            |

The broker test forces a PostgreSQL constraint failure, waits for the resulting NACK, removes the constraint, then waits
for the same broker message to succeed. It checks the document version and inbox count after the ACK callback. Its
second
subscription is read only after the order subscriber has ACKed, proving the consumers do not steal each other's
messages.
Asynchronous tests use bounded latches, futures, or condition polling rather than fixed sleeps as proof of completion.

Notification tests distinguish the domain event ID from the transport message ID: distinct events with a reused
message ID all commit, while a domain event redelivered with a new transport ID does not repeat the effect. They also
check persisted tenant, aggregate, correlation, selected summary fields, and exclusion of customer/storage details.

## Regression found and fixed

The new amount regression exposed binary floating-point conversion in the order service's JSON tree codec. Even
`12.00` lost its decimal scale. The codec now uses decimal nodes without stripping zeros, matching the notification
codec. The unit regression checks `0.01`, `12.00`, and `99999999999999999.99` through both envelope decoding and stored
outbox payload decoding. The full workflow checks that the maximum amount is persisted exactly and reaches the
notification consumer through the relay.

## Full workflow fixtures and recovery

`regression.py` creates unique orders and documents; it never truncates a database, changes shared SQL constraints, or
stops a running service. It uses the Compose project configured at the repository root. The recovery test deliberately
deletes only its own generated input object after its canonical report has committed, publishes the original request
twice, and observes stable result events through a temporary independent subscription. It checks the unchanged report
and document state and synchronously redelivers the result to the notification endpoint through the development bridge.
Temporary probe subscriptions are deleted in test cleanup; generated business records and reports remain for inspection.

The tests use the local development issuer and fixtures, not real customer data. Failed HTTP calls are reported without
signed URL query strings. The runner leaves the local stack running and never runs `down -v` automatically.

## Limits

Emulators and scripted SDK HTTP fixtures do not prove cloud IAM, real signed URL enforcement, CORS, retention policies,
dead-letter routing, network isolation, or managed-service failover. The shared JVM tests establish exact transaction
behavior; emulator tests establish adapter interoperability. Replay observations do not establish end-to-end
exactly-once
delivery. Business effects remain idempotent only within the documented inbox/report retention boundaries.

Load/soak tests, autoscaling budgets, operational SLOs, and cloud failure exercises remain in later deployment and
production-engineering phases. No arbitrary percentage coverage threshold substitutes for these behavior checks.

## Validation result

On 2026-09-21, `./scripts/local/test.sh` passed: 97 Maven tests with no failures/errors/skips, the full smoke check,
and all three packaged-service workflow regressions. The local stack remains running. The replay probe distinguishes
Pub/Sub message IDs so repeated delivery of one publication cannot count as two separate replay publications.

Next: Phase 11 — GKE.
