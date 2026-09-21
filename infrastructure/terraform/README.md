# Terraform

- `bootstrap`: existing-project state bucket, versioning, and a narrowly scoped state operator grant.
- `environments/{dev,staging,prod}`: independent GCS backends and explicit environment roots.
- `modules/platform`: shared regional platform, split by resource responsibility into readable files. Keeps IAM and
  service dependency wiring together rather than using empty one-resource module wrappers.

Provider versions and lock files are pinned. No credentials, secret payloads, or secret versions are created/read
through Terraform; Secret Manager containers and access grants are managed here. See [the runbook](../../docs/terraform.md)
for staged provisioning, user/schema preparation, provider validation, and Helm handoff.

```sh
./scripts/terraform/validate.sh
```

Requires Terraform 1.11+ (validated with 1.16.3); provider installation requires network access. All test providers
are mocked. The activation test uses a mock apply, which never calls GCP. Do not replace the mocks with real providers.
