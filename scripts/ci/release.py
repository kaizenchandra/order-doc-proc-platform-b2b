"""Build and publish the same scanned local images; never pass shell-expanded inputs."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

SERVICES = ("order", "document", "notification")


def validate_registry(value):
    if not re.fullmatch(r"[a-z0-9-]+-docker\.pkg\.dev/[a-z][a-z0-9-]+/[a-z][a-z0-9-]+", value):
        raise ValueError("RELEASE_REGISTRY must identify an Artifact Registry repository")
    return value


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def main(command):
    registry = validate_registry(os.environ["REGISTRY"])
    commit = os.environ["GITHUB_SHA"]
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("Invalid source commit")
    base = os.environ["JAVA_IMAGE"]
    if not re.fullmatch(r"[^\s]+@sha256:[a-f0-9]{64}", base):
        raise ValueError("JAVA_IMAGE must be pinned to an approved digest")
    if command == "build":
        for service in SERVICES:
            subprocess.run(["docker", "build", "--platform", "linux/amd64", "--build-arg", f"JAVA_IMAGE={base}",
                            "--label", f"org.opencontainers.image.revision={commit}", "-f",
                            f"services/{service}-service/Dockerfile", "-t", f"local/{service}-service:{commit}", "."], check=True)
    elif command == "publish":
        subprocess.run(["gcloud", "auth", "configure-docker", registry.split('/')[0], "--quiet"], check=True)
        images = {}
        for service in SERVICES:
            tagged = f"{registry}/{service}-service:{commit}-{os.environ['GITHUB_RUN_ID']}"
            subprocess.run(["docker", "tag", f"local/{service}-service:{commit}", tagged], check=True)
            subprocess.run(["docker", "push", tagged], check=True)
            digests = json.loads(run("docker", "image", "inspect", tagged, "--format", "{{json .RepoDigests}}"))
            images[service] = next(d for d in digests if d.startswith(f"{registry}/{service}-service@sha256:"))
        output = Path("target/release")
        output.mkdir(parents=True, exist_ok=True)
        (output / "release.json").write_text(json.dumps({"schema": 1, "repository": os.environ["GITHUB_REPOSITORY"],
            "commit": commit, "run_id": os.environ["GITHUB_RUN_ID"], "registry": registry,
            "java_image": base, "images": images}, indent=2) + "\n")
    else:
        raise ValueError("Expected build or publish")


if __name__ == "__main__":
    main(sys.argv[1])
