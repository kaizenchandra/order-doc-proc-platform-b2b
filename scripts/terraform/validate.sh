#!/usr/bin/env sh
# Offline with respect to GCP: init downloads providers; tests mock all Google APIs.
set -eu
cd "$(dirname "$0")/../.."
TF="${TERRAFORM:-terraform}"
"$TF" fmt -check -recursive infrastructure/terraform
for root in bootstrap environments/dev environments/staging environments/prod modules/platform; do
  "$TF" -chdir="infrastructure/terraform/$root" init -backend=false -input=false -lockfile=readonly
  "$TF" -chdir="infrastructure/terraform/$root" validate
 done
"$TF" -chdir=infrastructure/terraform/modules/platform test
