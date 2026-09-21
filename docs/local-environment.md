# Phase 9 — Local environment

The root Compose project runs all three Java services, two independent PostgreSQL databases, Google's Pub/Sub emulator,
fake-gcs-server, and a development issuer/push bridge. No GCP project, credentials, or service-account key files are
needed.
Published ports bind to `127.0.0.1`. All configured passwords and identities are development fixtures.

## Start and verify

Prerequisites: JDK 21, Docker with Compose v2 or newer, and Python 3. First startup downloads Maven dependencies and
container
images. The Maven wrapper supplies Maven; the Python smoke check uses only the standard library. The support container
installs its pinned cryptography dependency during its build.

From the repository root:

```sh
./scripts/local/up.sh
```

On macOS with an invalid `JAVA_HOME`:

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./scripts/local/up.sh
```

The script packages the reactor, builds the service images, starts Compose, waits for usable endpoints, and runs the
smoke check. Packaging skips tests for startup speed; run verification separately:

```sh
./mvnw -B -ntp verify
python3 scripts/local/smoke.py
```

The smoke check creates fresh records each run. It verifies unauthenticated push rejection, idempotent order creation,
PDF upload, asynchronous result consumption, checksum agreement, report download, and exactly two committed audit/intent
pairs for the new order. It outputs IDs, never tokens or signed URLs. The PDF fixture exercises this processor's marker
and checksum validation; it is not evidence of full PDF parsing.

## Endpoints and connections

| Component             | Host endpoint                                             | Container destination                |
|-----------------------|-----------------------------------------------------------|--------------------------------------|
| Order API             | `http://localhost:8080/api/v1/orders`                     | `order-service:8080`                 |
| Document push         | `http://localhost:8081/internal/pubsub/document-requests` | `document-service:8080`              |
| Notification push     | `http://localhost:8082/internal/pubsub/events`            | `notification-service:8080`          |
| Development issuer    | `http://localhost:9000`                                   | `support:9000`                       |
| Pub/Sub emulator      | `localhost:8085`                                          | `pubsub:8085`                        |
| GCS fake              | `http://localhost:4443`                                   | `gcs:4443`                           |
| Order database        | `localhost:5433/orders`                                   | `order-db:5432/orders`               |
| Notification database | `localhost:5434/notifications`                            | `notification-db:5432/notifications` |

Order database user/password: `orders` / `local-orders-only`.
Notification database user/password: `notifications` / `local-notifications-only`.
These are separate containers, roles, and volumes; neither service receives the other's credentials.

`POST http://localhost:9000/token` returns a five-minute access token for audience `local-orders`, scopes `orders:read
orders:write`, and tenant `11111111-1111-4111-8111-111111111111`. Use it as `Authorization: Bearer <access_token>` with
Bruno, curl, or another HTTP client. `/jwks` exposes only the public key. Signing keys are generated in memory on
startup.
The issuer is deliberately unauthenticated and is a local testing tool, not an identity service to deploy.

## Local configuration and messaging

Compose explicitly activates Spring's `local` profile. The order and document services then construct no-credentials
GCS clients using `LOCAL_GCS_ENDPOINT`. Their production GCS configuration is excluded only under that profile.
The order service uses a temporary RSA signer and rewrites signed URL origins to `LOCAL_GCS_PUBLIC_ENDPOINT` so the
host client can use them. Regular profiles retain ADC and IAM signing. Storage still requires `STORAGE_ENABLED=true`.
No security filter is disabled: both push consumers validate signed JWTs, issuer, audience, expiry, and push identity.

The support container waits for emulators and idempotently initializes:

| Topic               | Subscription             | Delivery                      |
|---------------------|--------------------------|-------------------------------|
| `order-events`      | `notification-orders`    | Bridge → notification-service |
| `document-requests` | `document-requests`      | Bridge → document-service     |
| `document-results`  | `order-document-results` | order-service streaming pull  |
| `document-results`  | `notification-results`   | Bridge → notification-service |

All resources belong to project `local-platform`; buckets are `local-uploads` and `local-reports`.
Initialization accepts an already-existing resource without overwriting it. Applications start only after initialization
succeeds, and database consumers also wait for PostgreSQL health checks. The smoke check establishes application
readiness; a Compose container being “running” by itself is not sufficient.

The emulator pushes plain HTTP to fixed bridge routes. The bridge adds a short-lived, audience-specific signed JWT,
forwards the original wrapper, and acknowledges only after a successful downstream response. Failure/timeout returns
503 for redelivery. There is no configurable arbitrary forwarding URL or application auth bypass. The helper exists
because the local emulator does not reproduce Google-signed push authentication; it does not prove cloud IAM behavior.
See [Google's emulator documentation](https://docs.cloud.google.com/pubsub/docs/emulator) and
[push authentication](https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions).

## Stop, rebuild, and troubleshoot

```sh
docker compose ps
docker compose logs --tail=100 order-service document-service notification-service support
# Stop without deleting database or object volumes:
docker compose stop
# Resume the same containers:
docker compose start
```

After Java changes, rerun `./scripts/local/up.sh` to rebuild jars and images. To recreate everything from empty local
storage, `docker compose down -v` deletes this project's local databases and uploaded objects; then run the startup
script.
The startup script never deletes volumes automatically.

Pub/Sub emulator state is transient. Restarting/recreating it loses subscriptions and queued messages, unlike real
Pub/Sub.
Restart `support` to recreate resources and restart `order-service` to reconnect its subscriber. Already-published
outbox
records are not automatically replayed. For a clean exercise after broker loss, use a deliberate full reset or create
new
orders; persisted `QUEUED` documents from lost messages do not recover by themselves. Do not use this stack as durable
business storage. Restarting support rotates its signing key; request fresh user tokens afterward.

For a failed smoke check, inspect service state and logs first. Common causes are occupied host ports, Docker not
running,
missing JDK 21, blocked image downloads, or stale emulator resources. GCS access logs are suppressed to avoid recording
signed URL queries. The helper also suppresses HTTP request logs.

## Emulator limits and validation

fake-gcs-server does not validate signed URL signatures or expiration, and local SDK URL rewriting intentionally breaks
the original host signature. Local testing does not establish GCS header/precondition enforcement, IAM, CORS, object
retention, or cloud networking correctness. Those checks still require the dedicated cloud validation described in
[Cloud Storage](cloud-storage.md). See
the [fake-gcs-server signed URL caveats](https://github.com/fsouza/fake-gcs-server#using-with-signed-urls).
Pub/Sub IAM, dead-letter policy, production retry/backoff, and managed-service availability are outside this local
proof.

Validated on 2026-09-21: Compose configuration, image builds and startup, full Maven reactor verification (88 tests, no
failures or skips, including
Phase 8 PostgreSQL/HTTP tests), and the complete smoke flow against the real local emulators and databases.

Phase 10 adds `./scripts/local/test.sh` for reactor verification plus smoke and recovery regressions.
See [testing](testing.md).
