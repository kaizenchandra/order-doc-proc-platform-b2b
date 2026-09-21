# Phase 11 — GKE application deployment

This phase adds an order-service release for an existing **regional GKE Autopilot** cluster: a Helm chart, namespace
prerequisite, deployment Dockerfile, application health probes, Cloud SQL Java connector, and validation tools.
Cloud Run resources remain Phase 12; cluster/VPC/Cloud SQL/IAM provisioning remains Phase 13. Nothing here has been
installed into a GKE cluster, and example resource names and the example image digest are deliberately fictional.

## Ownership and prerequisites

| Owner                       | Resources/responsibility                                                                                                                                                            |
|-----------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Platform / Terraform        | Regional Autopilot cluster, private networking, Google APIs, Artifact Registry, Cloud SQL, buckets, topics/subscriptions, IAM grants, DNS, global address, existing SSL certificate |
| Platform / secret delivery  | `order-platform` namespace, existing `order-database` Kubernetes Secret, credential rotation                                                                                        |
| Migration identity/workflow | Apply the order service's Flyway migrations with DDL privileges before rollout                                                                                                      |
| Helm release                | Deployment, Service, Kubernetes ServiceAccount, ConfigMap, HPA, PDB, NetworkPolicy, optional Ingress/BackendConfig                                                                  |

The application requires a migrated order database; its Helm configuration sets `DB_MIGRATIONS_ENABLED=false` and
Hibernate validates the schema. There is no migration hook that silently grants application pods DDL privileges.
The schema/migration execution mechanism and Secret Manager synchronization must be provided by the platform before
installation. Do not copy password values into Helm values, ConfigMaps, rendered YAML, or command-line arguments.

Apply `infrastructure/kubernetes/namespace.yaml` as a platform prerequisite when a cluster is ready. It enables
restricted
Pod Security admission (enforcement rules pinned to Kubernetes 1.30); review that pin when upgrading the cluster.
The application chart supports Kubernetes 1.30+ APIs, with schema validation performed against 1.34.0. This is an API
compatibility check, not a recommendation to select an old or unsupported GKE release. Use an appropriate supported
regional Autopilot release
channel. [Autopilot security](https://docs.cloud.google.com/kubernetes-engine/docs/concepts/autopilot-security)

## Runtime identity and database access

For release `orders` in namespace `order-platform`, the Kubernetes ServiceAccount is `orders-order`. Configure
`serviceAccount.googleServiceAccount` with the intended IAM service account and grant that KSA permission to impersonate
it.
The Workload Identity binding member is:

```text
serviceAccount:PROJECT_ID.svc.id.goog[order-platform/orders-order]
```

The binding uses `roles/iam.workloadIdentityUser` on the runtime IAM service account. The chart supplies the matching
`iam.gke.io/gcp-service-account` annotation. It does not create IAM bindings. No static service-account key is mounted;
ADC uses the GKE metadata server. Kubernetes API token automount is disabled because the process does not call the
Kubernetes
API. [GKE Workload Identity setup](https://docs.cloud.google.com/kubernetes-engine/docs/how-to/workload-identity)

Runtime capability requirements:

- Publish to the order-events and document-requests topics; subscribe to the independent order result subscription.
- Read upload object metadata; invoke IAM `signBlob` on the configured storage signing identity.
- Connect to the Cloud SQL instance (`roles/cloudsql.client`); database username/password authorize SQL operations.
- The signing identity can create uploads and read reports. Runtime roles must not permit report overwrite/deletion.
- Cluster/node image-pull identity can read the selected Artifact Registry repository; workload identity does not grant
  the node image-pull permission automatically.

The chart builds a JDBC URL using `com.google.cloud.sql.postgres.SocketFactory` and `ipTypes=PRIVATE`. The image
contains
the pinned Cloud SQL Java connector, version 1.30.0. Database username/password come from the existing Secret keys
`username` and `password` (configurable names), independently of ADC. The connector secures and authorizes connectivity;
it does not create a VPC route. Its data path uses TCP
3307. [Java connector configuration](https://github.com/GoogleCloudPlatform/cloud-sql-jdbc-socket-factory/blob/main/docs/jdbc.md),
[connector network requirements](https://github.com/GoogleCloudPlatform/cloud-sql-jdbc-socket-factory#firewall-configuration)

## Image and configuration

Build from the repository root after verification. Target the same architecture configured in the chart (default amd64):

```sh
./mvnw -B -ntp -pl services/order-service -am verify
docker build --platform linux/amd64 -f services/order-service/Dockerfile -t order-service:verified .
```

Publish through the release process and record the resulting registry digest. Set `image.repository` and `image.digest`
(`sha256:...`); mutable tags are not accepted. The base image can be pinned via the Docker build argument `JAVA_IMAGE`.
The runtime uses UID/GID 10001, a read-only root filesystem, dropped capabilities, RuntimeDefault seccomp, no privilege
escalation, and a bounded 128 MiB writable `/tmp`. No cloud credentials are baked into the image.

Copy `values.example.yaml` to a private environment-specific values file and replace all example identifiers. Required
inputs include the image digest, cloud project, runtime/signing identities, database connection name and Secret name,
HTTPS JWT issuer/JWK URL and audience, distinct buckets, messaging resource IDs, and exact Cloud SQL private IP CIDRs.
The schema rejects missing inputs, HTTP identity endpoints, non-digest images, and unsupported extra values. The chart
activates `gke`, never `local`, uses cloud adapters, and explicitly clears the Pub/Sub emulator endpoint.

ConfigMap changes alter the pod-template checksum and trigger a rollout. Secret content changes do **not** alter that
checksum; restart the deployment after rotating environment-variable credentials. Do not put secret checksums derived
from plaintext passwords into Helm values.

## Health, draining, and availability

`GET /livez` and `/readyz` are anonymous, status-only endpoints on the application's actual port 8080. Other Actuator
paths remain denied by application security. Liveness depends only on internal Spring availability, not databases or
Google APIs; readiness also checks the database. A shared database outage can therefore remove every replica from
service, but it will not trigger liveness restart storms. Probes are not a complete monitor of relay/subscriber
progress;
backlog and worker supervision remain in Phase
15. [Spring Boot probes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes)

A startup probe allows up to 180 seconds. Rolling updates allow one extra pod and no unavailable replicas. A PDB retains
at least one available pod during voluntary disruptions; it does not protect against every outage. Hostname spread is
required and zone spread is preferred, avoiding a hard three-zone requirement when capacity is limited.

Pod termination allows 180 seconds. A 45-second preStop delay gives endpoint/NEG removal time before SIGTERM; Boot then
drains HTTP requests and the messaging lifecycle stops the relay/subscriber. Backend draining is configured for 30
seconds.
These starting values require measurement on the real load balancer; they are not a guarantee of zero dropped requests.
Requests, outbox publications, and messages must retain their documented retry/idempotency behavior during rollout.

## Autoscaling and SQL budget

CPU-based HPA defaults to 2–4 replicas at 70% utilization, with 500m CPU / 1 GiB memory requested per pod and explicit
limits. Scale-down waits five minutes and removes at most one pod per minute. CPU scaling is an initial policy; it does
not directly track Pub/Sub backlog or SQL saturation. Autopilot can adjust resource requests to its supported ranges;
inspect the admitted pods and tune using load measurements.

Each pod has a five-connection pool. The chart checks an order-service-only connection allowance against:

```text
(2 × maximum replicas + 1 surge pod) × pool size <= order-service connection budget
```

Defaults reserve 50 connections against a 45-connection estimate, allowing a normal rollout's old/terminating pods as
well as replacement pods. This is a conservative planning check, not a database-enforced hard cap: unusual overlapping
rollouts or other clients can exceed it. Reserve notification-service, migrations, administration and failover capacity
separately within the actual Cloud SQL budget. Without HPA, the check uses `replicaCount` instead.

## HTTPS ingress and network policy

Ingress is opt-in. When enabled, it requires an existing global static address, existing Compute Engine SSL certificate,
and DNS hostname. It selects GKE's `gce` controller, disables HTTP, and uses container-native load balancing via NEGs.
Only `/api/v1/orders` is publicly routed. BackendConfig health checks call `/readyz` directly on port 8080 rather than
relying on probe inference. DNS/certificate provisioning is not part of this Helm release.
[Ingress configuration](https://docs.cloud.google.com/kubernetes-engine/docs/how-to/ingress-configuration)

NetworkPolicy isolates these pods in both directions. It allows inbound TCP 8080 from the configured Google
load-balancer
proxy/health-check CIDRs; outbound DNS to kube-dns/node-local DNS; metadata server ports 80/8080 for Dataplane V2;
TCP 3307 to the exact Cloud SQL private addresses; and TCP 443 for Google APIs and the identity provider. The 443 rule
is deliberately broad because standard Kubernetes NetworkPolicy cannot match FQDNs. Verify DNS addresses, VPC routes,
firewalls, Private Google Access/NAT needs, and actual proxy sources in the target cluster. This policy does not replace
VPC/IAM
controls. [GKE NetworkPolicy and metadata access](https://docs.cloud.google.com/kubernetes-engine/docs/how-to/network-policy)

## Validate before installation

These commands do not read kubeconfig or deploy anything:

```sh
python3 -m venv target/k8s-tools
target/k8s-tools/bin/pip install -r scripts/kubernetes/requirements.txt
helm lint infrastructure/helm/order-service -f infrastructure/helm/order-service/values.example.yaml --strict
target/k8s-tools/bin/python scripts/kubernetes/test_chart.py
helm template orders infrastructure/helm/order-service --namespace order-platform \
  -f infrastructure/helm/order-service/values.example.yaml > target/order-gke.yaml
```

Validate standard objects with kubeconform v0.7.0 (install through your normal tooling, or use `GOBIN="$PWD/target/k8s-bin"
go install github.com/yannh/kubeconform/cmd/kubeconform@v0.7.0`):

```sh
target/k8s-bin/kubeconform -strict -summary -kubernetes-version 1.34.0 -skip BackendConfig \
  target/order-gke.yaml infrastructure/kubernetes/namespace.yaml
```

BackendConfig is a GKE CRD and is explicitly excluded from generic Kubernetes schemas; its fields and wiring are checked
by the chart contract tests. GKE server-side validation is still required. With the local stack already running, the
optional `./scripts/kubernetes/test_runtime.sh` builds the deployment image and checks it with non-root/read-only
settings
against local dependencies on port 18080. It removes its temporary container and environment file afterward.

## Install and verify on a prepared cluster

The following are runbook commands, not actions performed in this phase. First review the explicit kube-context and
prepare all platform prerequisites, including migrations, identity grants, Secret, topics, buckets, and certificate:

```sh
kubectl --context YOUR_GKE_CONTEXT apply -f infrastructure/kubernetes/namespace.yaml
helm template orders infrastructure/helm/order-service --namespace order-platform \
  -f /path/to/environment-values.yaml > target/order-deployment.yaml
kubectl --context YOUR_GKE_CONTEXT --namespace order-platform apply --dry-run=server -f target/order-deployment.yaml
helm upgrade --install orders infrastructure/helm/order-service --kube-context YOUR_GKE_CONTEXT \
  --namespace order-platform -f /path/to/environment-values.yaml --wait --timeout 15m
kubectl --context YOUR_GKE_CONTEXT --namespace order-platform rollout status deployment/orders-order
kubectl --context YOUR_GKE_CONTEXT --namespace order-platform get pods,hpa,pdb,svc,ingress
```

Verify the admitted pod settings, readiness, image digest, NEG/backend health, HTTPS certificate, authenticated API
calls,
Cloud SQL private connectivity, outbox progress, result consumption, and authorized upload/report URLs. Exercise a
rolling
update and one voluntary eviction only in an appropriate test environment. A Helm success alone is not end-to-end proof.

For a failed rollout inspect pod events and application logs without printing Secret data. Use `helm history` and an
explicit `helm rollback orders REVISION --wait --timeout 15m` with the same context/namespace if the previous image
remains
compatible with the migrated schema. Image rollback does not undo SQL migrations, events, objects, or business effects.

## Validation result

Validated on 2026-09-21:

- Order-service reactor verification: 56 tests, no failures or skips, including status-only probes, readiness
  transitions,
  and unchanged API authentication requirements.
- Helm strict lint and seven offline chart contract tests pass, including invalid-input rejection and
  internal/fixed-size rendering.
- Kubernetes 1.34.0 strict schema validation: nine standard resources valid; one GKE BackendConfig explicitly skipped
  by the generic validator and checked by the chart tests.
- Deployment image build and full local workflow pass under non-root, read-only filesystem, dropped-capability, 1 GiB
  memory and 1 CPU constraints. The temporary runtime container was removed; the normal local stack remains running.

No real GKE admission, Workload Identity token exchange, Cloud SQL connector connection, or HTTPS load-balancer rollout
was performed. Those depend on the platform prerequisites and require the server-side checks above.

Next: Phase 12 — Cloud Run.
