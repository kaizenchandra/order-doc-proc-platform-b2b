# Phase 13 — Terraform platform

The Terraform implementation provisions one regional platform in an existing, billing-enabled project. Use a separate
project and state bucket for dev, staging, and prod. The shared module is `infrastructure/terraform/modules/platform`;
each environment root fixes its environment name and uses a separate GCS backend. No cloud resources have been applied
and no live project plan has been run in this phase.

## Ownership

| Terraform manages | Deployment/operator workflow manages |
| --- | --- |
| Required project APIs and service agents | Existing project, billing, org policy, and deployment identity |
| Custom VPC, private GKE subnet/ranges, Run subnet, NAT, private service access | Private runner/VPN access to the GKE control plane |
| Regional Autopilot cluster, custom node identity, Workload Identity grant | Namespace, Kubernetes Secret delivery, Helm order-service release |
| Private PostgreSQL 17 instance, orders/notifications databases, backups/PITR | Database login creation, passwords, SQL grants, Flyway migrations |
| Artifact Registry repository and node pull grant | Build, scan, publish, and select immutable images |
| Private upload/report buckets and scoped object grants | Retention/replay policy and controlled cleanup |
| Three event topics, four independent subscriptions, per-consumer DLQs, retained inspection subscriptions, log metrics and alerts | Verified notification channels and controlled replay operations |
| Cloud Run v2 services, runtime and push identities, invoker/token grants | Cloud acceptance tests and measured capacity tuning |
| Secret Manager containers and notification accessor grant | Secret payload versions and rotation |
| Global API address, optional managed certificate and optional DNS A record | Existing authoritative DNS zone and Helm-managed HTTPS load balancer |

Passwords never enter Terraform variables, data sources, plans, or state. Terraform intentionally does not manage SQL
login passwords or Secret Manager payload versions. The `database_prepared` activation input is an operator attestation,
not an automated database check. It prevents accidental service startup before the separate preparation workflow.

## Resource choices and boundaries

The VPC has no default subnet. GKE nodes use `10.20.0.0/20`, pods `10.24.0.0/14`, services `10.28.0.0/20`, Cloud Run
`10.20.16.0/24`, SQL private service access `10.30.0.0/16`, and the private GKE control plane `172.16.0.0/28`. These
ranges are disjoint within the project. Review them against VPN/peering networks before applying; this baseline does
not attempt to allocate CIDRs across a corporate network. NAT serves GKE external HTTPS needs; Google API access is
also enabled privately on both subnets. Notification uses Direct VPC private-range egress; document has no VPC attachment.
The VPC's implied egress allow remains in effect; application NetworkPolicy is described in [GKE](gke.md).

GKE uses the REGULAR release channel and a private control-plane endpoint. A runner with appropriate regional VPC
reachability is required for Helm, Secret delivery, and migrations. Terraform itself uses Google management APIs and
does not instantiate a Kubernetes provider or require cluster connectivity. The custom node account can pull images;
the order workload uses `order-platform/orders-order` to impersonate its separate runtime account.

Cloud SQL uses a 2-vCPU/7.5-GiB Enterprise instance, 20-GiB auto-growing disk, seven retained backups and seven-day PITR.
Staging and prod use regional HA; dev uses a zonal instance to reduce cost. Public SQL IPv4 is disabled, connector
traffic is encrypted, and both Terraform and the API protect deletion. The 200-connection setting leaves room above
the initial 50-order + 50-notification planning budgets; enforce runtime user connection limits and reserve capacity
for administration/migrations. These are initial limits, not a load-tested capacity result. See the pinned
[SQL provider contract](https://github.com/hashicorp/terraform-provider-google/blob/v8.1.0/website/docs/r/sql_database_instance.html.markdown).

Buckets enforce uniform access and public-access prevention, retain object versions, and reject force deletion. No
automatic object expiration is configured until cleanup and replay windows are agreed. Runtime grants cannot delete
canonical reports. The dedicated signing account creates uploads and reads reports; order can sign through that
identity without a private key. Runtime/push identities and their permissions remain separate.

Source subscriptions retain unacknowledged messages for seven days and never auto-expire. Each has its own dead-letter
topic and a 31-day inspection subscription, with both Pub/Sub service-agent forwarding grants. During foundation
provisioning all four subscriptions use pull mode so they can retain events before Cloud Run is activated. Switching
three to authenticated push preserves the same subscription resources and backlog. Dead-letter attempts are approximate;
monitor and replay deliberately. [Pub/Sub dead-letter behavior](https://docs.cloud.google.com/pubsub/docs/dead-letter-topics)

Cloud Run services translate the [Phase 12 contract](cloud-run.md) into v2 resources: internal ingress, enabled IAM
checks, exact custom audiences, per-service push identities, probes, pinned secret reference, private SQL, and revision
limits. Subscriptions depend on service readiness and invoker grants; application subscription-name configuration is
constructed from stable names, avoiding a service/subscription dependency cycle. The order subscription always stays
pull. [Cloud Run provider schema](https://github.com/hashicorp/terraform-provider-google/blob/v8.1.0/website/docs/r/cloud_run_v2_service.html.markdown)

## Tooling and state bootstrap

Validated with Terraform 1.16.3 and Google/google-beta providers 8.1.0. Provider versions and generated lock files are
checked into each root and the test module. Use ADC or short-lived service-account impersonation from an authorized
platform identity; never write service-account JSON keys into this repository. The execution identity needs the
resource-specific provisioning/IAM permissions plus `iam.serviceAccounts.actAs` on runtime, node, and push identities.
Organization policies and regional quotas must permit the proposed services. Bootstrap and application state are not
credentials and still require restricted access: they describe infrastructure and may acquire sensitive values later.

The following commands are a runbook, not commands executed in this phase. Bootstrap once per project using a private
working copy so environments do not reuse the same local bootstrap state:

```sh
mkdir -p target/bootstrap-dev
cp infrastructure/terraform/bootstrap/main.tf infrastructure/terraform/bootstrap/.terraform.lock.hcl target/bootstrap-dev/
terraform -chdir=target/bootstrap-dev init
terraform -chdir=target/bootstrap-dev plan \
  -var='project_id=YOUR_DEV_PROJECT' \
  -var='bucket_name=YOUR_UNIQUE_DEV_STATE_BUCKET' \
  -var='state_admin_member=serviceAccount:YOUR_PLATFORM_IDENTITY' \
  -out=bootstrap.tfplan
# Review the plan, then apply that saved plan with the authorized project identity.
terraform -chdir=target/bootstrap-dev apply bootstrap.tfplan
```

Move this bootstrap state to its newly created bucket before deleting the private working copy. Add a `backend.tf`
with the following content to that private copy:

```hcl
terraform {
  backend "gcs" {}
}
```

Then run:

```sh
terraform -chdir=target/bootstrap-dev init -migrate-state \
  -backend-config='bucket=YOUR_UNIQUE_DEV_STATE_BUCKET' -backend-config='prefix=bootstrap'
```

The bucket enables versioning and uniform access and grants the nominated platform principal object administration
only on that bucket. GCS backend supports state locking; retain version history for recovery. Use separate buckets
and principals per environment when access boundaries differ. [GCS backend requirements](https://developer.hashicorp.com/terraform/language/backend/gcs)

## Stage 1: foundation

Copy the selected root's `terraform.example.tfvars` to ignored `terraform.tfvars`. Replace the project ID and review
the region. Copy `backend.example.hcl` to an external/private backend configuration, selecting the same environment's
bucket and its unique `order-platform/ENV` prefix. Keep `enable_cloud_run=false` and `database_prepared=false`.

```sh
terraform -chdir=infrastructure/terraform/environments/dev init -reconfigure \
  -backend-config=/private/path/dev-backend.hcl
terraform -chdir=infrastructure/terraform/environments/dev plan -out=foundation.tfplan
terraform -chdir=infrastructure/terraform/environments/dev show foundation.tfplan
# After reviewing resource names, permissions, region, costs, and deletion behavior:
terraform -chdir=infrastructure/terraform/environments/dev apply foundation.tfplan
```

This is a provisioned GKE/SQL/NAT baseline and incurs costs even without traffic. Apply through the normal authorized
deployment process; `enable_cloud_run=false` is not a cost-free mode. Read non-secret handoff data with
`terraform output -json deployment`. Do not use `-target` to work around the bootstrap sequence.

## Prepare credentials, database schemas, and images

From an authorized private-network migration runner:

1. Create distinct runtime logins named `orders` and `notifications` and a separate migration/owner identity. Runtime
   users must have no superuser, role-management, database-creation, schema-creation, or migration ownership rights.
   Cloud SQL API-created users can receive elevated default roles: explicitly remove those before application use.
2. Revoke PUBLIC connection rights to both databases and grant each runtime login CONNECT only on its own database.
   Revoke PUBLIC CREATE on each public schema. Grant the migration identity the DDL privileges needed for its service.
3. Run each service's Flyway migrations against its own database with the migration identity. Grant the corresponding
   runtime login schema USAGE and required table DML; order needs SELECT/INSERT/UPDATE on its domain/outbox/inbox tables,
   notification needs SELECT/INSERT on its inbox/audit/notification tables. Runtime cleanup DELETE permissions must be
   considered separately. Configure default privileges as the migration owner for future application tables.
4. Set each runtime login's connection limit to 50; confirm it cannot connect to the other service database and cannot
   create/drop tables. These SQL checks are required evidence before setting `database_prepared=true`.
5. Generate and store the two passwords through the approved credential workflow; add values directly as versions of
   the Terraform-created Secret Manager containers. Do not put payloads in tfvars, command-line flags, logs, or state.
   Record the notification numeric version. Deliver the order username/password as Kubernetes Secret `order-database`
   (keys `username`, `password`) using the authorized platform secret-delivery identity. No runtime identity receives
   project-wide secret access; delivery permissions belong to that separate workflow.
6. Build and publish both consumer images into the created Artifact Registry repository and record their Linux amd64
   digests. Supply them in the `images` map. Build/publish order-service for its Helm release as described in [GKE](gke.md).

The platform intentionally leaves these steps explicit instead of running local-exec migrations or capturing passwords
through provider data sources. They are not implied to have happened by a successful foundation apply.

## Stage 2: activate and verify

Set `database_prepared=true`, the two digest references, and `notification_password_version` to an existing numeric
version, then set `enable_cloud_run=true`. Terraform rejects missing/mutable images, `latest` secrets, and unattested
schema preparation. Plan to a new saved plan, review it, and apply through the same workflow. Internal `run.app` URLs
and matching OIDC audiences are wired automatically. Same-project Pub/Sub delivery is required by this ingress design.

Export the platform-specific Helm fragment:

```sh
terraform -chdir=infrastructure/terraform/environments/dev output -raw helm_platform_values > target/helm-dev-platform.yaml
```

Merge it with order-service release values containing the image digest, identity-provider HTTPS issuer/JWKS/audience,
and `database.existingSecret=order-database`. Use release `orders`, namespace `order-platform`, and the private cluster
context. Terraform creates no namespace or Helm resources. Apply the namespace prerequisite and follow [the Helm
runbook](gke.md). Supplying an API hostname creates a managed certificate; attach it via Helm and point authoritative
DNS at the global IP. The certificate stays provisioning until DNS and the load balancer are ready. An optional existing
Cloud DNS zone enables Terraform's A record; otherwise DNS remains externally managed.

Cloud acceptance remains required: authenticate an order request, upload via a signed URL, confirm asynchronous report
creation and order completion, verify two notification intents, replay an event, test unauthorized push rejection, and
check private SQL connections. Inspect live invoker policies for inherited/public grants and confirm exact JWT delivery
through Google's frontend. Mock tests cannot prove organization policy, IAM propagation, capacity, network routes,
real token verification, or managed API acceptance. No live project was supplied for these checks in this phase.

## Updates, rollback, and destruction

Keep schemas backward-compatible across overlapping image revisions. Roll back by planning the prior digest/secret
version; Terraform controls 100% latest-ready traffic. Do not disable `enable_cloud_run` as a pause switch after activation:
that requests service deletion and is blocked by deletion protection. Keep source subscriptions and durable business
records intact during rollback. Each push endpoint update retains the source subscription and outstanding backlog.

Review every destroy/replacement explicitly. SQL/databases, document buckets, secrets, and state bucket have
`prevent_destroy`; SQL/GKE/Cloud Run also use provider/API deletion protection. Removal of a resource block can bypass
Terraform's `prevent_destroy` guard, so review plans rather than assuming it is an authorization boundary. Back up and
prove restore before any deliberate decommission; no force-delete workflow is supplied. Never share state or use
Terraform workspaces as the only environment boundary.

## Local validation

```sh
./scripts/terraform/validate.sh
# Or, when using a workspace-local Terraform binary:
TERRAFORM="$PWD/target/terraform-tools/terraform" ./scripts/terraform/validate.sh
```

The script checks formatting, initializes with `-backend=false`, validates bootstrap, all three roots and the module,
and runs nine mock scenarios. Initialization can download providers but does not access GCP state. The activation test
performs only a mocked apply; every Google provider is mocked. See [Terraform mock providers](https://developer.hashicorp.com/terraform/language/tests/mocking).

Validation completed on 2026-09-22: recursive formatting passed; bootstrap, dev, staging, prod, and the shared module
all passed provider schema validation; all seven mock scenarios passed with no skips. Shell syntax and repository
whitespace checks passed. No live cloud plan, cloud apply, or cloud acceptance test was performed.

Phase 14 narrows signing, object access, and push-token grants; see [the current IAM contract](security.md).

Phase 15 adds operational metrics and thirteen alert policies. Supply existing verified `notification_channels`; an empty list creates console incidents only. See [operations](operations.md).
