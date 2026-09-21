"""Fail-closed release/input/plan checks shared by the deployment jobs."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from release import SERVICES, validate_registry

OUT = Path("target/deployment")


def validate_release(release, run, repository, run_id, registry):
    if not re.fullmatch(r"[1-9][0-9]*", run_id):
        raise ValueError("Release run ID must be numeric")
    if (run.get("conclusion") != "success" or run.get("status") != "completed"
            or run.get("event") != "workflow_dispatch" or run.get("head_branch") != "main"
            or run.get("path") != ".github/workflows/release.yml"
            or run.get("repository", {}).get("full_name") != repository or str(run.get("id")) != run_id):
        raise ValueError("Release must come from this repository's successful main publish workflow")
    if (release.get("schema") != 1 or release.get("repository") != repository or release.get("run_id") != run_id
            or release.get("commit") != run.get("head_sha") or not re.fullmatch(r"[a-f0-9]{40}", release.get("commit", ""))
            or release.get("registry") != validate_registry(registry)):
        raise ValueError("Release manifest provenance does not match")
    if set(release.get("images", {})) != set(SERVICES):
        raise ValueError("Release must contain exactly three images")
    for service in SERVICES:
        if not re.fullmatch(re.escape(f"{registry}/{service}-service") + r"@sha256:[a-f0-9]{64}", release["images"][service]):
            raise ValueError("Image must be a digest in the approved release repository")


def validate_plan(plan):
    allowed = {"google_cloud_run_v2_service", "google_cloud_run_v2_service_iam_member"}
    changes = []
    for resource in plan.get("resource_changes", []):
        actions = resource["change"]["actions"]
        if actions in (["no-op"], ["read"]):
            continue
        if "delete" in actions or (resource["type"] not in allowed and not (
                resource["type"] == "google_pubsub_subscription" and actions == ["update"]
                and resource["address"].startswith('module.platform.google_pubsub_subscription.consumer['))):
            raise ValueError("Infrastructure/destructive change requires the separate platform workflow: " + resource["address"])
        changes.append({"address": resource["address"], "actions": actions})
    return changes


def target():
    env = os.environ["DEPLOY_ENV"]
    if env not in ("dev", "staging", "prod"):
        raise ValueError("Unknown deployment environment")
    project = os.environ["GCP_PROJECT_ID"]
    bucket = os.environ["STATE_BUCKET"]
    if not re.fullmatch(r"[a-z][a-z0-9-]{4,28}[a-z0-9]", project) or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{1,61}[a-z0-9]", bucket):
        raise ValueError("Invalid project/state bucket")
    return env, project, bucket


def main(command):
    OUT.mkdir(parents=True, exist_ok=True)
    if command == "provenance":
        run_id = os.environ["RELEASE_RUN_ID"]
        if not re.fullmatch(r"[1-9][0-9]*", run_id):
            raise ValueError("Release run ID must be numeric")
        result = subprocess.check_output(["gh", "api", f"repos/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{run_id}"], text=True)
        Path("target/release-run.json").write_text(result)
    elif command == "prepare":
        env, project, bucket = target()
        release = json.loads(Path("target/release/release.json").read_text())
        run = json.loads(Path("target/release-run.json").read_text())
        validate_release(release, run, os.environ["GITHUB_REPOSITORY"], os.environ["RELEASE_RUN_ID"], os.environ["REGISTRY"])
        values = json.loads(os.environ["TFVARS_JSON"])
        allowed = {"project_id", "region", "enable_cloud_run", "database_prepared", "notification_password_version",
                   "api_hostname", "dns_managed_zone", "notification_channels"}
        if set(values) - allowed or values.get("project_id") != project or values.get("database_prepared") is not True:
            raise ValueError("Use approved non-secret Terraform inputs and attest database preparation")
        if env == "prod" and not values.get("notification_channels"):
            raise ValueError("Production requires operational notification channels")
        values.update(enable_cloud_run=True, images={s: release['images'][s] for s in ('document', 'notification')})
        helm = json.loads(os.environ["HELM_VALUES_JSON"])
        # These inputs are deliberately narrow: credentials never enter plans or Helm artifacts.
        if set(helm) != {"app", "database"} or set(helm["app"]) != {"authIssuer", "authJwkSetUri", "authAudience"} or set(helm["database"]) != {"existingSecret"}:
            raise ValueError("HELM_VALUES_JSON must contain only auth settings and an existing Secret name")
        repository, digest = release["images"]["order"].split("@")
        helm["image"] = {"repository": repository, "digest": digest}
        (OUT / "inputs.tfvars.json").write_text(json.dumps(values))
        (OUT / "helm-values.json").write_text(json.dumps(helm))
        (OUT / "manifest.json").write_text(json.dumps({"environment": env, "project": project, "bucket": bucket,
            "workflow_commit": os.environ["GITHUB_SHA"], "workflow_run": os.environ["GITHUB_RUN_ID"],
            "release": release, "plan_object": f"release-plans/{os.environ['GITHUB_RUN_ID']}-{os.environ['GITHUB_RUN_ATTEMPT']}/deployment.tfplan"}, indent=2))
    elif command == "review":
        changes = validate_plan(json.loads((OUT / "plan.json").read_text()))
        (OUT / "changes.json").write_text(json.dumps(changes, indent=2))
        (OUT / "plan.sha256").write_text(hashlib.sha256((OUT / "deployment.tfplan").read_bytes()).hexdigest())
    elif command == "check-apply":
        env, project, bucket = target()
        manifest = json.loads((OUT / "manifest.json").read_text())
        if any(manifest[k] != v for k, v in {"environment": env, "project": project, "bucket": bucket,
                "workflow_commit": os.environ["GITHUB_SHA"], "workflow_run": os.environ["GITHUB_RUN_ID"]}.items()):
            raise ValueError("Reviewed deployment is for another target or workflow revision/run")
        if not re.fullmatch(r"release-plans/[0-9]+-[0-9]+/deployment\.tfplan", manifest["plan_object"]):
            raise ValueError("Invalid plan storage path")
        print(f"gs://{bucket}/{manifest['plan_object']}")
    elif command == "check-hash":
        if hashlib.sha256((OUT / "deployment.tfplan").read_bytes()).hexdigest() != (OUT / "plan.sha256").read_text():
            raise ValueError("Saved plan does not match the reviewed digest")
    else:
        raise ValueError("Unknown deployment operation")


if __name__ == "__main__":
    main(sys.argv[1])
