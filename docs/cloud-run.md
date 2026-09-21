# Cloud Run runtime and deployment contract

Phase 12 provides non-root Java 21 images, runtime probes, and reference service manifests for document-service and
notification-service. Phase 13 Terraform owns deployed services, IAM, secrets, networking, and subscriptions. The
examples under `infrastructure/cloud-run` are reviewable inputs for that implementation, not a second deployment owner.
No cloud resources have been created or updated.

## Runtime

| Setting | document-service | notification-service |
| --- | --- | --- |
| CPU / memory | 1 / 1 GiB | 1 / 1 GiB |
| Concurrent requests per instance | 2 | 5 |
| Revision instances | 0–4 | 0–4 |
| Request timeout | 180 seconds | 30 seconds |
| Pub/Sub acknowledgment deadline | 240 seconds | 60 seconds |
| Startup probe | GET /readyz, every 5 seconds, 36 failures | Same; includes database connectivity |
| Liveness probe | GET /livez, every 10 seconds, 3 failures | Same; excludes database connectivity |

These are initial operating limits, not load-tested capacity claims. Notification's five-connection pool matches its
concurrency. Allow at least 50 notification database connections for two overlapping four-instance revisions (40),
migrations, and administrative access. Cloud Run can temporarily exceed instance limits; the database connection
limit remains the final guard. Keep the order-service budget separate and size the shared SQL instance for both.

Both applications honor `PORT` (default 8080), bind using Spring's default all-interface address, run as UID/GID 10001,
and expose only status on `/livez` and `/readyz`. Other Actuator URLs remain denied. Platform probes reach the container;
an anonymous health route inside Spring does not bypass Cloud Run IAM at the public endpoint. A dependency outage
fails notification requests without making liveness restart every instance.

Java is PID 1 and receives SIGTERM. Spring allows eight seconds per shutdown phase; this is a best-effort drain,
not a promise that all shutdown phases fit the platform's ten-second termination window. Interrupted processing relies
on Pub/Sub retry, canonical reports, and transactional inbox deduplication. No local filesystem state is durable.
Cloud Run injects the listening port and requires Linux amd64 images. See the
[container contract](https://docs.cloud.google.com/run/docs/container-contract).

Request-based CPU allocation is suitable because handlers finish durable processing and publication before ACK;
there are no background consumers or outbox schedulers in these two services. Notification uses the Cloud SQL Java
connector with `ipTypes=PRIVATE` and `cloudSqlRefreshStrategy=lazy`, avoiding scheduled credential refresh under CPU
throttling. Direct VPC egress must reach the SQL private address on TCP 3307; the connector still needs Google APIs
on HTTPS. SQL must have private service connectivity, the subnet must be in the service region, and firewall rules
must allow this route. See [Cloud SQL connection guidance](https://docs.cloud.google.com/sql/docs/postgres/connect-run).
Document-service needs no VPC or database connection.

## Authentication and delivery

Each service has its own runtime service account and a separate push invocation service account. Grant only that
push identity `roles/run.invoker` on its receiving service. Keep the invoker IAM check enabled; do not grant
`allUsers` or `allAuthenticatedUsers`. Configure Pub/Sub to issue OIDC tokens for the exact custom audience in both
the manifest's service annotation and `PUSH_AUDIENCE`. Replace the example audience with a stable environment-specific
identifier; it need not resolve in DNS. This avoids needing the generated service URL before configuring the service.

Pub/Sub sends the JWT through `Authorization`. Spring also verifies Google's signature, issuer, expiration, audience,
and the configured verified service-account email. Do not route push authentication through `X-Serverless-Authorization`,
whose signature Cloud Run removes before container delivery. Keep the Google issuer/JWKS defaults and never use the
local token bridge in cloud deployments. See [Cloud Run authentication](https://docs.cloud.google.com/run/docs/authenticating/service-to-service)
and [custom audiences](https://docs.cloud.google.com/run/docs/configuring/custom-audiences).

Use internal ingress and the default HTTPS `run.app` service URL as each subscription's push endpoint. Pub/Sub and
Cloud Run must be in the same project for this contract; cross-project delivery needs a separately designed perimeter.
Keep the default URL enabled. Internal ingress alone does not authenticate callers.
See [internal ingress recognition](https://docs.cloud.google.com/run/docs/securing/ingress).

| Subscription | Topic | Endpoint path | Push identity / audience |
| --- | --- | --- | --- |
| document-requests | document-requests | /internal/pubsub/document-requests | document push identity / document audience |
| notification-orders | order-events | /internal/pubsub/events | notification push identity / notification audience |
| notification-results | document-results | /internal/pubsub/events | notification push identity / notification audience |

Subscription names in the application must be full `projects/PROJECT_ID/subscriptions/NAME` paths. Retain wrapped
Pub/Sub JSON delivery; payload unwrapping is unsupported. Configure retry backoff (10–600 seconds), seven-day message
retention, and a dead-letter topic per consumer with a retained inspection subscription and an initial ten-attempt
limit. Dead-letter forwarding is best effort, not an exact delivery counter. Terraform must grant the Pub/Sub service
agent publisher on the dead-letter topic and subscriber on the source subscription, plus token creation permission
on the push service account. The provisioning identity needs `iam.serviceAccounts.actAs` on that push identity.
See [authenticated push](https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions)
and [dead-letter topics](https://docs.cloud.google.com/pubsub/docs/dead-letter-topics).

HTTP 204 acknowledges only completed durable work. Non-success responses and expired deadlines cause retry. Platform
timeouts do not guarantee application cancellation; repeated and overlapping requests must retain current idempotency
behavior. Preserve the independent order result subscription when adding notification delivery.

## Identities, secrets, and rollout

- Document runtime: read upload objects; read/create canonical report objects; publish to document-results. No SQL,
  signing-key files, invoker role, or project-wide storage administrator role.
- Notification runtime: Cloud SQL Client and Secret Manager accessor on its database-password secret only. SQL user
  permissions are scoped to its database; migrations use a separate deployment identity.
- Push identities: only invoke their respective service; no runtime storage, SQL, or publishing permissions.

Inject `DB_PASSWORD` using `secretKeyRef` with a numeric version, not `latest`; a secret rotation requires a new revision.
The example username is configuration, not a password. Set `DB_MIGRATIONS_ENABLED=false`; apply schema migrations before
starting a revision. Hibernate validates the schema and fails startup if it is missing. See
[Secret Manager integration](https://docs.cloud.google.com/run/docs/configuring/services/secrets).

Build executable jars and Linux amd64 images from the repository root:

```sh
./mvnw -B -ntp -DskipTests package
docker build --platform linux/amd64 -f services/document-service/Dockerfile -t document-service:phase12 .
docker build --platform linux/amd64 -f services/notification-service/Dockerfile -t notification-service:phase12 .
```

For releases, pin `JAVA_IMAGE` to an approved digest, push to Artifact Registry, and record each resulting image digest.
Replace every uppercase placeholder in the reference manifests, including numeric secret versions. All ordinary env
values remain strings. The manifests follow the [Cloud Run YAML reference](https://docs.cloud.google.com/run/docs/reference/yaml/v1).
Terraform must translate the complete contract into v2 service resources; do not separately run `gcloud services replace`
once Terraform owns them.

Provision network, databases/schema, secrets, topics and runtime identities first. Create services with authenticated
internal ingress, then invoker grants and push subscriptions using the resulting service URLs. Before promotion,
exercise a real authenticated Pub/Sub workflow: verify report creation, order completion, and one notification intent
per event; replay a message; verify unauthorized access fails; check no sustained push 401/403/5xx or SQL exhaustion.
An ordinary laptop cannot directly test internal ingress. Local tests do not prove cloud IAM, Google-issued JWT
forwarding, Direct VPC, or managed scaling. Validate those in the development project in Phase 13.

Roll back to the previous known image/configuration and pinned secret version through Terraform. Keep migrations
backward-compatible across overlapping revisions and retain stable audiences/subscriptions during rollback. Never
roll back by deleting inbox records or canonical reports.

## Validation

Run `./mvnw -B -ntp -pl services/document-service,services/notification-service -am verify` for both consumer reactors.
The integration tests exercise status-only probes, denied Actuator access, JWT rejection, and existing durable
processing and transaction boundaries. Reference manifests were parsed and checked for matching audiences,
separate runtime/push identities, private SQL configuration, secret references, and probes. Managed Cloud Run
acceptance remains a cloud deployment check rather than a claim from local parsing.

On 2026-09-21, the two-service reactor passed 50 tests with no failures, errors, or skips. Both new Dockerfiles built,
and the local smoke workflow passed with those images: order retry, upload, asynchronous processing, checksum/report
download, and two notification intents. This used local emulators and the local profile, not Google cloud resources.
