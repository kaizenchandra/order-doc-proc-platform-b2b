# order-service Helm chart

Application release for an existing regional GKE Autopilot cluster. This chart does not create cloud infrastructure,
IAM grants, database credentials, database schemas, DNS, or certificates. See the [GKE runbook](../../../docs/gke.md).

Required values have no deployable defaults. `values.example.yaml` is a lint/render fixture with fake resources and a
fake digest. Copy and replace its values for your environment before installation. Never put credentials in values
files.

```sh
helm lint infrastructure/helm/order-service -f infrastructure/helm/order-service/values.example.yaml --strict
helm template orders infrastructure/helm/order-service --namespace order-platform \
  -f infrastructure/helm/order-service/values.example.yaml
```

The release owns Deployment, ClusterIP Service, Kubernetes ServiceAccount, ConfigMap, HPA, PDB, NetworkPolicy and, when
enabled, GKE Ingress/BackendConfig. The namespace and existing database Secret are platform-owned prerequisites.
Application probes are on port 8080. The public Ingress routes only `/api/v1/orders`; it does not route probe/Actuator
paths.

The chart name is derived from the release (`orders` produces `orders-order`). IAM's Workload Identity member must match
that Kubernetes ServiceAccount and the chosen namespace. See `values.schema.json` for the supported input surface.
