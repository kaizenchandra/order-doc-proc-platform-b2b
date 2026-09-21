# Infrastructure ownership

`local/` contains the local service/support Dockerfiles and dependency pin, used by the root Compose file.
See [local environment](../docs/local-environment.md). `helm/order-service` and the Kubernetes namespace prerequisite
are implemented; see the [GKE runbook](../docs/gke.md).
Terraform now provides bootstrap, environment roots, and a shared platform module; see the [Terraform runbook](../docs/terraform.md). No cloud resources have been provisioned.

- `terraform/environments/{dev,staging,prod}`: separate root configurations and state boundaries (Phase 13). Never share
  production state with development.
- `terraform/modules`: resource modules, wired by environment roots. IAM bindings stay close to their resource ownership
  to avoid dependency cycles.
- `kubernetes`: cluster/namespace prerequisites and explanatory resources (Phase 11).
- `helm/order-service`: application release resources (Phase 11); avoid managing the same Kubernetes object through Helm
  and Terraform.
- Cloud Run service resources belong to Terraform (Phase 12 design, Phase 13 implementation).

Container digests are deployment inputs. Secrets and Terraform state are never committed. Terraform `sensitive` masks
output; it does not remove values from state. Environment promotion must not copy secret values between environments.

Cloud Run consumer images and reference deployment contracts are implemented; see the [Cloud Run runbook](../docs/cloud-run.md).
