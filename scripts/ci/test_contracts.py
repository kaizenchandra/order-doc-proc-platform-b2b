import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import yaml

from deployment import main, validate_plan, validate_release
from release import validate_registry

ROOT = Path(__file__).resolve().parents[2]


class ReleaseContracts(unittest.TestCase):
    def setUp(self):
        self.registry = 'asia-south1-docker.pkg.dev/test-project/releases'
        self.run = {'id': 123, 'conclusion': 'success', 'status': 'completed', 'event': 'workflow_dispatch',
                    'head_branch': 'main', 'head_sha': 'a' * 40, 'path': '.github/workflows/release.yml',
                    'repository': {'full_name': 'owner/repository'}}
        self.release = {'schema': 1, 'repository': 'owner/repository', 'run_id': '123', 'commit': 'a' * 40,
                        'registry': self.registry, 'images': {
                            service: f'{self.registry}/{service}-service@sha256:' + 'b' * 64
                            for service in ['order', 'document', 'notification']}}

    def check(self):
        validate_release(self.release, self.run, 'owner/repository', '123', self.registry)

    def test_accepts_same_repository_successful_publish_digests(self):
        self.check()

    def test_rejects_failed_foreign_or_untrusted_workflow_runs(self):
        original = copy.deepcopy(self.run)
        for key, value in [('conclusion', 'failure'), ('event', 'pull_request'), ('head_branch', 'feature'),
                           ('path', '.github/workflows/verify.yml'), ('id', 456),
                           ('repository', {'full_name': 'attacker/repository'})]:
            with self.subTest(key=key):
                self.run = dict(original, **{key: value})
                with self.assertRaises(ValueError): self.check()

    def test_rejects_substituted_commit_image_or_missing_service(self):
        original = copy.deepcopy(self.release)
        for change in ['commit', 'tag', 'registry', 'missing']:
            self.release = copy.deepcopy(original)
            if change == 'commit': self.release['commit'] = 'c' * 40
            if change == 'tag': self.release['images']['order'] = self.registry + '/order-service:latest'
            if change == 'registry': self.release['images']['order'] = 'other/' + self.release['images']['order']
            if change == 'missing': del self.release['images']['document']
            with self.subTest(change=change), self.assertRaises(ValueError): self.check()

    def test_registry_rejects_command_text_and_non_registry_hosts(self):
        for value in ['$(whoami)', '--flag', 'example.com/repository', self.registry + '\nextra']:
            with self.subTest(value=value), self.assertRaises(ValueError): validate_registry(value)

    def test_application_plan_allows_only_non_destructive_service_changes(self):
        def change(kind, actions, address=None):
            return {'type': kind, 'address': address or 'module.platform.' + kind + '.consumer', 'change': {'actions': actions}}
        allowed = [change('google_cloud_run_v2_service', ['update']),
                   change('google_cloud_run_v2_service_iam_member', ['create']),
                   change('google_pubsub_subscription', ['update'], 'module.platform.google_pubsub_subscription.consumer["document-requests"]')]
        self.assertEqual(3, len(validate_plan({'resource_changes': allowed})))
        for item in [change('google_sql_database_instance', ['update']), change('google_cloud_run_v2_service', ['delete', 'create']),
                     change('google_pubsub_subscription', ['delete']), change('google_storage_bucket', ['create'])]:
            with self.subTest(item=item), self.assertRaises(ValueError): validate_plan({'resource_changes': [item]})


class DeploymentArtifacts(unittest.TestCase):
    def setUp(self):
        ReleaseContracts.setUp(self)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        previous = Path.cwd()
        os.chdir(temporary.name)
        self.addCleanup(os.chdir, previous)
        Path('target/release').mkdir(parents=True)
        Path('target/release/release.json').write_text(json.dumps(self.release))
        Path('target/release-run.json').write_text(json.dumps(self.run))
        self.values = {'project_id': 'test-project', 'database_prepared': True,
                       'notification_password_version': '1'}
        self.helm = {'app': {'authIssuer': 'https://issuer.example',
                            'authJwkSetUri': 'https://issuer.example/jwks', 'authAudience': 'orders'},
                     'database': {'existingSecret': 'custom-order-database'}}
        environment = patch.dict(os.environ, {'DEPLOY_ENV': 'dev', 'GCP_PROJECT_ID': 'test-project',
            'STATE_BUCKET': 'test-state-bucket', 'GITHUB_REPOSITORY': 'owner/repository',
            'RELEASE_RUN_ID': '123', 'REGISTRY': self.registry, 'TFVARS_JSON': json.dumps(self.values),
            'HELM_VALUES_JSON': json.dumps(self.helm), 'GITHUB_SHA': 'c' * 40,
            'GITHUB_RUN_ID': '456', 'GITHUB_RUN_ATTEMPT': '1'})
        environment.start()
        self.addCleanup(environment.stop)

    def test_prepared_images_come_from_verified_release(self):
        main('prepare')
        values = json.loads(Path('target/deployment/inputs.tfvars.json').read_text())
        self.assertTrue(values['enable_cloud_run'])
        self.assertEqual(self.release['images']['document'], values['images']['document'])
        helm = json.loads(Path('target/deployment/helm-values.json').read_text())
        self.assertEqual('sha256:' + 'b' * 64, helm['image']['digest'])
        self.assertEqual('custom-order-database', helm['database']['existingSecret'])

    def test_rejects_secret_inputs_and_unprepared_database(self):
        for changed in [dict(self.values, password='secret'), dict(self.values, database_prepared=False)]:
            with patch.dict(os.environ, TFVARS_JSON=json.dumps(changed)), self.assertRaises(ValueError):
                main('prepare')
        self.helm['database']['password'] = 'secret'
        with patch.dict(os.environ, HELM_VALUES_JSON=json.dumps(self.helm)), self.assertRaises(ValueError):
            main('prepare')

    def test_production_requires_notification_channels(self):
        with patch.dict(os.environ, DEPLOY_ENV='prod'), self.assertRaises(ValueError):
            main('prepare')

    def test_apply_rejects_other_target_revision_run_and_storage_path(self):
        main('prepare')
        for key, value in [('DEPLOY_ENV', 'prod'), ('GCP_PROJECT_ID', 'other-project'),
                           ('STATE_BUCKET', 'other-bucket'), ('GITHUB_SHA', 'd' * 40), ('GITHUB_RUN_ID', '789')]:
            with self.subTest(key=key), patch.dict(os.environ, {key: value}), self.assertRaises(ValueError):
                main('check-apply')
        manifest_path = Path('target/deployment/manifest.json')
        manifest = json.loads(manifest_path.read_text())
        manifest['plan_object'] = 'order-platform/dev/default.tfstate'
        manifest_path.write_text(json.dumps(manifest))
        with self.assertRaises(ValueError): main('check-apply')

    def test_apply_rejects_modified_saved_plan(self):
        main('prepare')
        Path('target/deployment/plan.json').write_text('{"resource_changes": []}')
        plan = Path('target/deployment/deployment.tfplan')
        plan.write_bytes(b'reviewed plan')
        main('review')
        main('check-hash')
        plan.write_bytes(b'substituted plan')
        with self.assertRaises(ValueError): main('check-hash')


class WorkflowContracts(unittest.TestCase):
    def setUp(self):
        # BaseLoader preserves YAML's 'on' key rather than interpreting it as a YAML 1.1 boolean.
        self.workflows = {p.stem: yaml.load(p.read_text(), Loader=yaml.BaseLoader)
                          for p in (ROOT / '.github/workflows').glob('*.yml')}

    def test_external_actions_are_immutable_and_checkout_does_not_persist_credentials(self):
        for workflow in self.workflows.values():
            for job in workflow['jobs'].values():
                for step in job.get('steps', []):
                    if 'uses' not in step: continue
                    self.assertRegex(step['uses'], r'^[A-Za-z0-9_-]+/[A-Za-z0-9_-]+@[a-f0-9]{40}$')
                    if step['uses'].startswith('actions/checkout@'):
                        self.assertEqual('false', step['with']['persist-credentials'])

    def test_untrusted_ci_has_no_cloud_permissions_or_private_runners(self):
        workflow = self.workflows['verify']
        self.assertNotIn('pull_request_target', workflow['on'])
        self.assertEqual({'contents': 'read'}, workflow['permissions'])
        for job in workflow['jobs'].values():
            self.assertEqual('ubuntu-24.04', job['runs-on'])
            self.assertNotIn('id-token', job.get('permissions', {}))

    def test_deployment_requires_plan_before_environment_gate(self):
        workflow = self.workflows['deploy']
        self.assertEqual(['workflow_dispatch'], list(workflow['on']))
        self.assertEqual('plan', workflow['jobs']['apply']['needs'])
        self.assertEqual('${{ inputs.environment }}', workflow['jobs']['apply']['environment'])
        self.assertEqual('false', workflow['concurrency']['cancel-in-progress'])
        self.assertIn("github.ref == 'refs/heads/main'", workflow['jobs']['plan']['if'])
        uploads = [s for s in workflow['jobs']['plan']['steps'] if s.get('uses', '').startswith('actions/upload-artifact@')]
        self.assertNotIn('tfplan', uploads[0]['with']['path'])
        self.assertNotIn('plan.json', uploads[0]['with']['path'])

    def test_release_scans_all_images_before_cloud_authentication(self):
        job = self.workflows['release']['jobs']['publish']
        self.assertEqual('verify', job['needs'])
        steps = job['steps']
        auth = next(i for i, s in enumerate(steps) if s.get('uses', '').startswith('google-github-actions/auth@'))
        scans = [(i, s) for i, s in enumerate(steps) if s.get('uses', '').startswith('aquasecurity/trivy-action@')]
        self.assertEqual(3, len(scans))
        for i, scan in scans:
            self.assertLess(i, auth)
            self.assertEqual('1', scan['with']['exit-code'])
            self.assertEqual('false', scan['with']['ignore-unfixed'])


if __name__ == '__main__':
    unittest.main()
