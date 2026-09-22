#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
DEPLOY_DIR="$PWD/target/deployment"
TF_ROOT="infrastructure/terraform/environments/$DEPLOY_ENV"
PLAN_URI=$(python3 scripts/ci/deployment.py check-apply)
gcloud storage cp "$PLAN_URI" "$DEPLOY_DIR/deployment.tfplan"
python3 scripts/ci/deployment.py check-hash
terraform -chdir="$TF_ROOT" init -input=false -lockfile=readonly -reconfigure \
  -backend-config="bucket=$STATE_BUCKET" -backend-config="prefix=order-platform/$DEPLOY_ENV"
# Terraform itself rejects a stale saved plan; never silently re-plan after approval.
terraform -chdir="$TF_ROOT" apply -input=false -lock-timeout=5m "$DEPLOY_DIR/deployment.tfplan"
terraform -chdir="$TF_ROOT" output -json deployment > "$DEPLOY_DIR/platform.json"
read -r CLUSTER REGION < <(python3 - <<'PY'
import json
from pathlib import Path
p=json.loads(Path('target/deployment/platform.json').read_text())
print(p['cluster_name'],p['region'])
PY
)
export KUBECONFIG="$DEPLOY_DIR/kubeconfig"
export USE_GKE_GCLOUD_AUTH_PLUGIN=True
gcloud container clusters get-credentials "$CLUSTER" --region "$REGION" --project "$GCP_PROJECT_ID" --internal-ip
# Cluster namespace, migrations, and Kubernetes Secret are explicit platform prerequisites.
DATABASE_SECRET=$(python3 - <<'PY'
import json
from pathlib import Path
print(json.loads(Path('target/deployment/helm-values.json').read_text())['database']['existingSecret'])
PY
)
kubectl --namespace order-platform get secret "$DATABASE_SECRET" -o name
helm upgrade --install orders infrastructure/helm/order-service --namespace order-platform \
  -f "$DEPLOY_DIR/platform-values.yaml" -f "$DEPLOY_DIR/helm-values.json" --atomic --wait --timeout 15m
kubectl --namespace order-platform rollout status deployment/orders-order --timeout=5m
kubectl --namespace order-platform get pods,hpa,pdb,svc,ingress
