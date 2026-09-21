#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
DEPLOY_DIR="$PWD/target/deployment"
TF_ROOT="infrastructure/terraform/environments/$DEPLOY_ENV"
terraform -chdir="$TF_ROOT" init -input=false -lockfile=readonly -reconfigure \
  -backend-config="bucket=$STATE_BUCKET" -backend-config="prefix=order-platform/$DEPLOY_ENV"
# A foundation must already exist. CI/CD does not bootstrap databases, credentials, or clusters.
terraform -chdir="$TF_ROOT" output -raw helm_platform_values > "$DEPLOY_DIR/platform-values.yaml"
terraform -chdir="$TF_ROOT" plan -input=false -lock-timeout=5m \
  -var-file="$DEPLOY_DIR/inputs.tfvars.json" -out="$DEPLOY_DIR/deployment.tfplan" > "$DEPLOY_DIR/plan.txt"
terraform -chdir="$TF_ROOT" show -json "$DEPLOY_DIR/deployment.tfplan" > "$DEPLOY_DIR/plan.json"
python3 scripts/ci/deployment.py review
helm template orders infrastructure/helm/order-service --namespace order-platform \
  -f "$DEPLOY_DIR/platform-values.yaml" -f "$DEPLOY_DIR/helm-values.json" > "$DEPLOY_DIR/helm-preview.yaml"
PLAN_URI=$(python3 scripts/ci/deployment.py check-apply)
gcloud storage cp --if-generation-match=0 "$DEPLOY_DIR/deployment.tfplan" "$PLAN_URI"
# Only non-secret review artifacts enter GitHub; full plans/state stay in the restricted state bucket.
python3 - <<'PY'
import json, os
from pathlib import Path
p=Path('target/deployment');m=json.loads((p/'manifest.json').read_text())
with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as out:
    out.write('## Deployment review\n\n')
    out.write(f"Target: `{m['environment']}` / `{m['project']}`\n\n")
    out.write(f"Release commit: `{m['release']['commit']}`\n\n")
    out.write(f"Saved plan: `gs://{m['bucket']}/{m['plan_object']}`\n\n")
    out.write(f"Plan SHA-256: `{(p/'plan.sha256').read_text()}`\n\n")
    out.write('Download the restricted saved plan and run `terraform show` with these locked providers. Review the Helm preview artifact before approving the environment gate.\n')
PY
rm -f "$DEPLOY_DIR/deployment.tfplan" "$DEPLOY_DIR/plan.json" "$DEPLOY_DIR/plan.txt" "$DEPLOY_DIR/inputs.tfvars.json"
