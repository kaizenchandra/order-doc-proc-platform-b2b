"""Offline Helm contract tests. Does not read kubeconfig or contact a Kubernetes cluster."""
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
CHART = ROOT / "infrastructure/helm/order-service"
EXAMPLE = yaml.safe_load((CHART / "values.example.yaml").read_text())


def render(overrides=None, example=True):
    with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml") as values:
        yaml.safe_dump(overrides or {}, values)
        values.flush()
        command = ["helm", "template", "orders", str(CHART), "--namespace", "order-platform", "--kube-version", "1.34.0"]
        if example:
            command += ["-f", str(CHART / "values.example.yaml")]
        return subprocess.run(command + ["-f", values.name], capture_output=True, text=True, timeout=30)


class ChartTests(unittest.TestCase):
    def resources(self, overrides=None):
        result = render(overrides)
        self.assertEqual(0, result.returncode, result.stderr)
        documents = [item for item in yaml.safe_load_all(result.stdout) if item]
        kinds = {item["kind"]: item for item in documents}
        self.assertEqual(len(documents), len(kinds), "Unexpected duplicate resource kinds")
        return kinds

    def test_deployment_security_probes_and_identity(self):
        resources = self.resources()
        deployment = resources["Deployment"]
        pod = deployment["spec"]["template"]
        container = pod["spec"]["containers"][0]
        self.assertIn("@sha256:", container["image"])
        self.assertTrue(pod["spec"]["securityContext"]["runAsNonRoot"])
        self.assertFalse(pod["spec"]["automountServiceAccountToken"])
        self.assertTrue(container["securityContext"]["readOnlyRootFilesystem"])
        self.assertFalse(container["securityContext"]["allowPrivilegeEscalation"])
        self.assertEqual(["ALL"], container["securityContext"]["capabilities"]["drop"])
        for probe in ["startupProbe", "livenessProbe", "readinessProbe"]:
            self.assertEqual("http", container[probe]["httpGet"]["port"])
        self.assertEqual("/readyz", container["readinessProbe"]["httpGet"]["path"])
        self.assertEqual("/livez", container["livenessProbe"]["httpGet"]["path"])
        self.assertEqual(resources["ServiceAccount"]["metadata"]["name"], pod["spec"]["serviceAccountName"])
        self.assertEqual(EXAMPLE["serviceAccount"]["googleServiceAccount"],
                         resources["ServiceAccount"]["metadata"]["annotations"]["iam.gke.io/gcp-service-account"])
        self.assertEqual(deployment["spec"]["selector"]["matchLabels"], resources["Service"]["spec"]["selector"])
        self.assertEqual("ClusterIP", resources["Service"]["spec"]["type"])
        self.assertNotIn("replicas", deployment["spec"], "HPA must own replica count during upgrades")

    def test_credentials_are_references_and_cloud_paths_are_explicit(self):
        resources = self.resources()
        self.assertNotIn("Secret", resources)
        config = resources["ConfigMap"]["data"]
        self.assertEqual("false", config["DB_MIGRATIONS_ENABLED"])
        self.assertEqual("gke", config["SPRING_PROFILES_ACTIVE"])
        self.assertIn("ipTypes=PRIVATE", config["DB_URL"])
        self.assertIn("com.google.cloud.sql.postgres.SocketFactory", config["DB_URL"])
        self.assertEqual("", config["PUBSUB_EMULATOR_HOST"])
        self.assertNotIn("DB_PASSWORD", config)
        env = resources["Deployment"]["spec"]["template"]["spec"]["containers"][0]["env"]
        for entry in env:
            self.assertNotIn("value", entry)
            self.assertEqual("order-database", entry["valueFrom"]["secretKeyRef"]["name"])

    def test_https_ingress_uses_readiness_health_check_and_routes_only_api(self):
        resources = self.resources()
        ingress = resources["Ingress"]
        self.assertEqual("false", ingress["metadata"]["annotations"]["kubernetes.io/ingress.allow-http"])
        paths = ingress["spec"]["rules"][0]["http"]["paths"]
        self.assertEqual(["/api/v1/orders"], [path["path"] for path in paths])
        self.assertEqual("/readyz", resources["BackendConfig"]["spec"]["healthCheck"]["requestPath"])
        self.assertEqual(8080, resources["BackendConfig"]["spec"]["healthCheck"]["port"])
        self.assertIn(resources["BackendConfig"]["metadata"]["name"], resources["Service"]["metadata"]["annotations"]["cloud.google.com/backend-config"])

    def test_internal_fixed_size_release_has_no_ingress_or_hpa(self):
        resources = self.resources({"ingress": {"enabled": False}, "autoscaling": {"enabled": False}, "replicaCount": 3})
        for kind in ["Ingress", "BackendConfig", "HorizontalPodAutoscaler"]:
            self.assertNotIn(kind, resources)
        self.assertEqual(3, resources["Deployment"]["spec"]["replicas"])
        self.assertEqual(1, resources["PodDisruptionBudget"]["spec"]["minAvailable"])

    def test_network_policy_keeps_sql_and_metadata_ports_distinct(self):
        policy = self.resources()["NetworkPolicy"]["spec"]
        self.assertEqual(["Ingress", "Egress"], policy["policyTypes"])
        rules = {item["to"][0].get("ipBlock", {}).get("cidr"): item for item in policy["egress"]}
        self.assertEqual({3307}, {port["port"] for port in rules["10.20.0.3/32"]["ports"]})
        self.assertEqual({80, 8080}, {port["port"] for port in rules["169.254.169.254/32"]["ports"]})
        self.assertEqual({443}, {port["port"] for port in rules["0.0.0.0/0"]["ports"]})

    def test_config_changes_trigger_a_rollout(self):
        before = self.resources()["Deployment"]["spec"]["template"]["metadata"]["annotations"]["checksum/config"]
        after = self.resources({"app": {"authAudience": "changed-audience"}})["Deployment"]["spec"]["template"]["metadata"]["annotations"]["checksum/config"]
        self.assertNotEqual(before, after)

    def test_incomplete_or_unsafe_values_fail_before_installation(self):
        self.assertNotEqual(0, render(example=False).returncode)
        invalid = [
            {"image": {"digest": "latest"}},
            {"database": {"existingSecret": ""}},
            {"database": {"name": "orders?ipTypes=PUBLIC"}},
            {"database": {"connectionBudget": 10}},
            {"autoscaling": {"minReplicas": 5, "maxReplicas": 4}},
            {"replicaCount": 1},
            {"app": {"authIssuer": "http://issuer.test"}},
            {"app": {"uploadBucket": EXAMPLE["app"]["reportBucket"]}},
            {"app": {"orderEventsTopic": "document-requests"}},
            {"networkPolicy": {"sqlCidrs": []}},
            {"ingress": {"preSharedCertificate": ""}},
            {"extraEnv": {"SPRING_PROFILES_ACTIVE": "local"}},
        ]
        for values in invalid:
            with self.subTest(values=values):
                self.assertNotEqual(0, render(values).returncode)


if __name__ == "__main__":
    unittest.main(verbosity=2)
