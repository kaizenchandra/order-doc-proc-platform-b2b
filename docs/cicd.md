# CI/CD

Phase 16 implements GitHub Actions verification, image publication, and reviewed application deployment.
The workflows have been validated locally. They have not been dispatched against GitHub or GCP;
repository protections, federation, environment variables, runners, and cloud acceptance remain setup prerequisites.

## Workflows

| Workflow | Trigger | Behavior |
| --- | --- | --- |
| `verify.yml` | Pull requests, pushes to `main`, reusable call | Maven unit/integration tests, disposable Compose smoke and recovery tests, Terraform validation/mock tests, Helm and CI contracts, actionlint |
| `release.yml` | Manual dispatch on `main` | Runs verification, builds three Linux AMD64 images from verified jars, scans each image, produces CycloneDX SBOMs, authenticates through federation, publishes images and a digest manifest |
| `deploy.yml` | Manual dispatch on `main`, environment and release run ID | Checks successful release provenance, creates a saved Terraform plan and Helm preview, waits at the configured environment gate, applies that exact plan and rolls out the chart |

External actions use full commit SHAs. Dependabot proposes action, Maven, and Terraform updates.
Pull requests run on GitHub-hosted runners with read-only repository permission and no cloud identity.
Publication blocks on HIGH/CRITICAL vulnerabilities, including unfixed findings. A failed scan prevents
cloud authentication and publication. No image scan has been executed as part of local validation;
the gate must pass for the selected base image and application dependencies before a release can be published.

The release manifest binds all three digest references to the repository, commit, and successful publish
run. Deployment accepts only that repository's successful manual `main` publish workflow and its approved
Artifact Registry repository. Images are promoted by digest without rebuilding for each environment.
This is a workflow provenance check, not a cryptographic image attestation or an admission policy.

## Configure GitHub before enabling deployment

Protect `main` with review and the Verify checks. Restrict workflow and deployment-script edits to
reviewed changes. Create environments `release`, `dev-plan`, `staging-plan`, `prod-plan`, `dev`, `staging`,
and `prod`. Restrict all environments to `main`. Configure required reviewers and prevent self-review
for the apply environments; disable bypass where the repository's GitHub plan supports it. Environment
names alone do **not** enforce an approval gate. Confirm those protections before allowing deployment.

Set these GitHub **environment variables** (not credential payloads):

| Environment | Variables |
| --- | --- |
| `release` | `GCP_PROJECT_ID`, `WIF_PROVIDER`, `PUBLISH_SERVICE_ACCOUNT`, `RELEASE_REGISTRY`, `JAVA_IMAGE` |
| Each `*-plan` | `GCP_PROJECT_ID`, `WIF_PROVIDER`, `PLAN_SERVICE_ACCOUNT`, `STATE_BUCKET`, `RELEASE_REGISTRY`, `TFVARS_JSON`, `HELM_VALUES_JSON` |
| Each apply environment | `GCP_PROJECT_ID`, `WIF_PROVIDER`, `APPLY_SERVICE_ACCOUNT`, `STATE_BUCKET` |

`RELEASE_REGISTRY` is a shared approved repository such as
`asia-south1-docker.pkg.dev/your-build-project/releases`. `JAVA_IMAGE` must be an approved Java 21 runtime
reference pinned with `@sha256:<64 hex characters>` and support Linux AMD64. Select and maintain that digest
through review; the workflow does not invent one or fall back to a mutable tag.

Example `TFVARS_JSON` for a prepared dev foundation:

```json
{
  "project_id": "your-dev-project",
  "region": "asia-south1",
  "database_prepared": true,
  "notification_password_version": "1",
  "notification_channels": []
}
```

Preserve the foundation's existing region, hostname, DNS zone, and notification channels in these inputs.
Production requires at least one existing Monitoring notification channel. Database preparation is an
operator attestation: it must follow the migrations, users, grants, and secret setup in the
[Terraform runbook](terraform.md), not merely set a boolean. Image references and Cloud Run activation
are derived by the workflow. Extra Terraform inputs are rejected.

Example `HELM_VALUES_JSON`:

```json
{
  "app": {
    "authIssuer": "https://identity.example.com/",
    "authJwkSetUri": "https://identity.example.com/.well-known/jwks.json",
    "authAudience": "order-api"
  },
  "database": { "existingSecret": "order-database" }
}
```

Only these auth settings and an existing Kubernetes Secret name are accepted. Do not put passwords,
private keys, bearer tokens, or signed URLs in either JSON variable. The auth endpoints are public
configuration; a Secret name is a reference. Helm schema validation checks the merged deployment values.

## Cloud identities and runner prerequisites

Provision Workload Identity Federation separately under platform administration. Use distinct publisher,
planner, and deployer service accounts per trust boundary. Restrict the provider's attribute condition
and service-account federation bindings to the numeric GitHub repository/owner IDs, `refs/heads/main`,
the intended workflow reference, and the corresponding environment claim. Do not trust only a reusable
repository name or an entire identity pool. Validate rejected branch, repository, and environment tokens
before enabling writes. No downloaded service-account keys are needed.

Grant the publisher Artifact Registry writer only on the release repository. Grant the GKE node identity
and Cloud Run service agents in every target project Artifact Registry reader on that repository;
the platform's per-environment registry grants do not cover a separate central release project.

The planner needs resource read permissions for Terraform refresh, state read and locking permissions,
and permission to create saved plans under `release-plans/` in the restricted state bucket. It needs no
application resource write permission. The deployer needs state/lock access, saved-plan read access,
and narrowly scoped Cloud Run update, consumer subscription update, service IAM, and service-account
act-as permissions for the configured runtime/push identities. Derive and review a custom role against
the actual plan; do not grant project Owner/Editor. Initial Cloud Run creation also needs its corresponding
create permissions. Infrastructure changes outside the application allowlist require the separate
operator provisioning procedure in `docs/terraform.md`; there is no automated infrastructure workflow.

Apply jobs require isolated, ephemeral Linux AMD64 self-hosted runners labeled `order-platform-dev`,
`order-platform-staging`, or `order-platform-prod`. They must reach the target private GKE control plane
and have current GitHub runner software, Python 3, kubectl, and the prerequisites for the pinned setup
actions. Restrict runner groups to this deployment workflow; never route pull requests to these runners.
Grant cluster discovery IAM and namespace-scoped Kubernetes permissions sufficient for the chart and
Helm release records, including the named database Secret check. A role permitting Secret reads also
permits credential access: treat this as a trusted deployment identity. Runners must be destroyed after
jobs; the final cleanup removes deployment files and the auth action cleans its temporary credential file.

Create the Terraform foundation/state backend, `order-platform` namespace, database Secret, prepared
schemas and users, secret versions, and operational channels before the first application release.
The planner reads existing foundation outputs and cannot bootstrap an empty environment. Apply the
bootstrap bucket lifecycle addition through the operator procedure: it expires objects under
`release-plans/` after one day and leaves the state prefix unaffected.

## Review, deploy, and recover

1. Dispatch **Publish verified images** from `main`. Inspect verification and vulnerability results.
   Retain the successful run ID; its `release-manifest` artifact contains digest references and SBOMs
   for 90 days. Registry retention must keep those digests available throughout the rollback window.
2. Dispatch **Review and deploy release** from `main` with that run ID and `dev`.
3. Review the plan job summary and `deployment-plan` artifact: target, release commit, change addresses,
   platform values, and rendered Helm resources. Download the saved plan from the private GCS URI with
   an authorized operator identity, verify the displayed SHA-256, and inspect it with `terraform show`
   using the same Terraform version and locked providers. Full plans can contain state and belong in
   the restricted bucket, not GitHub artifacts or issue comments.
4. Approve the configured target environment only after reviewing that concrete result. Approval must
   occur within the one-day artifact/plan window; if it expires, start a new workflow and review its plan.
   The apply job checks target, workflow revision/run, and plan hash. Terraform rejects stale state;
   the workflow never silently replans after approval.
5. Inspect the Helm rollout, then perform the authenticated cloud acceptance checks in
   [GKE](gke.md), [Cloud Run](cloud-run.md), and [operations](operations.md): upload, document completion,
   notification persistence, duplicate delivery, and alert routing. A ready rollout alone does not prove
   the end-to-end workflow. Promote the same release run ID through staging and production after acceptance.

Application deployment allows non-destructive Cloud Run service/IAM changes and updates to the known
consumer subscriptions. Other resource changes, including unrelated drift and replacements, stop the plan
before approval. Resolve them through the operator provisioning procedure and generate a new plan.
Deployment concurrency is serialized per environment and does not cancel an active apply.

Cloud Run and GKE updates are not one transaction. Terraform applies first; Helm `--atomic` rolls back
its own failed upgrade, not Cloud Run. For an application rollback, dispatch a new reviewed deployment
using a retained, previously accepted release run ID and inspect compatibility before approval. Database
migrations are outside this workflow and must remain compatible with both releases. On a partial failure,
inspect Terraform state and actual revisions, preserve evidence, and create a fresh reviewed plan.

## Local validation

- 14 CI contract tests: release provenance, immutable images, untrusted-runner isolation, scan ordering,
  deployment inputs, production channels, target/revision binding, and saved-plan tampering.
- 7 Helm contract tests, including rendered security settings and invalid input rejection.
- Terraform formatting and validation for bootstrap, three environment roots, and the platform module;
  9 Google-provider mock scenarios. No GCP API calls or state changes.
- actionlint with optional external shellcheck/pyflakes disabled locally, plus Bash syntax validation.
  GitHub CI runs actionlint with its default integrations.

The Helm tests exposed separated template delimiters (`{ { ... } }`) in the checked-in YAML templates.
These were restored to valid Go-template delimiters; all seven rendering contracts now pass. Java sources
are unchanged in this phase; the full application suite remains part of the Verify workflow.
