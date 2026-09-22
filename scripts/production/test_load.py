import hashlib
import unittest
from urllib.error import HTTPError

from load import Workload, bounded, percentiles, safe_url


class WorkloadTests(unittest.TestCase):
    def workload(self, bad_report=False, bad_retry=False):
        calls = []
        content = b'%PDF-1.7\n%%EOF'
        checksum = hashlib.sha256(content).hexdigest()
        def transport(method, url, body, headers, **kwargs):
            calls.append((method, url, headers))
            if url == 'https://api.example/orders':
                return {'id': 'different' if bad_retry and len(calls) == 2 else 'order'}
            if url.endswith('/documents'):
                return {'document': {'id': 'document'}, 'upload': {'url': 'https://storage.example/upload',
                        'method': 'PUT', 'headers': {'signed': 'upload'}}}
            if url.endswith('/upload') or url.endswith('/complete'): return None
            if url.endswith('/report'):
                return {'url': 'https://storage.example/download', 'method': 'GET', 'headers': {}}
            if url.endswith('/download'): return {'sha256': 'incorrect' if bad_report else checksum}
            return {'status': 'PROCESSED', 'sha256': checksum}
        return Workload('https://api.example/orders', 'private-token', content, 10, transport=transport), calls

    def test_workflow_checks_retry_checksum_and_report_without_forwarding_api_token(self):
        workload, calls = self.workload()
        workload.workflow(0)
        self.assertEqual(1, workload.successes)
        self.assertEqual(calls[0][2]['Idempotency-Key'], calls[1][2]['Idempotency-Key'])
        for _, url, headers in calls:
            if 'storage.example' in url: self.assertNotIn('Authorization', headers)

    def test_incorrect_report_or_retry_counts_as_failure(self):
        for config in ({'bad_report': True}, {'bad_retry': True}):
            workload, _ = self.workload(**config)
            workload.workflow(0)
            self.assertEqual(0, workload.successes)
            self.assertEqual({'ValueError': 1}, workload.errors)

    def test_http_failure_records_only_status_without_capability(self):
        workload, _ = self.workload()
        def fail(*args, **kwargs):
            raise HTTPError('https://secret.example/signed?token=private', 503, 'private', {}, None)
        workload.transport = fail
        workload.workflow(0)
        self.assertEqual({'HTTP_503': 1}, workload.errors)
        self.assertEqual(1, len(workload.timings['workflow']))

    def test_endpoints_require_https_or_explicit_local_loopback(self):
        self.assertEqual('http://localhost:8080', safe_url('http://localhost:8080', True))
        for url in ('http://remote.example', 'https://user:password@remote.example', 'file:///tmp/token'):
            with self.assertRaises(ValueError): safe_url(url, True)

    def test_bounds_and_nearest_rank_latency(self):
        self.assertEqual({'p50_ms': 2000, 'p95_ms': 4000, 'p99_ms': 4000}, percentiles([4, 1, 3, 2]))
        self.assertEqual({}, percentiles([]))
        with self.assertRaises(ValueError): bounded(1, 16)('invalid')


if __name__ == '__main__':
    unittest.main()
