"""Bounded closed-loop document workload; stdout contains aggregate metrics only."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import threading
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
import uuid


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def safe_url(value, local=False):
    parsed = urlsplit(value)
    if parsed.username or parsed.password or parsed.fragment or not parsed.hostname:
        raise ValueError('Invalid endpoint')
    if parsed.scheme != 'https' and not (local and parsed.scheme == 'http'
                                        and parsed.hostname in ('localhost', '127.0.0.1')):
        raise ValueError('HTTPS required outside local mode')
    return value


def http(method, url, body=None, headers=None, local=False):
    safe_url(url, local)
    headers = dict(headers or {})
    if body is not None and not isinstance(body, bytes):
        body = json.dumps(body).encode()
        headers.setdefault('Content-Type', 'application/json')
    # Never forward bearer credentials or signed capabilities through redirects.
    with build_opener(NoRedirect()).open(Request(url, body, headers, method=method), timeout=20) as response:
        data = response.read(1024 * 1024 + 1)
        if len(data) > 1024 * 1024:
            raise ValueError('Response exceeds workload bound')
        return json.loads(data) if data else None


def percentiles(values):
    ordered = sorted(values)
    return {f'p{p}_ms': round(ordered[max(0, math.ceil(len(ordered) * p / 100) - 1)] * 1000, 2)
            for p in (50, 95, 99)} if ordered else {}


class Workload:
    def __init__(self, api, token, content, timeout, local=False, transport=http):
        self.api, self.content, self.timeout, self.local = api, content, timeout, local
        self.auth = {'Authorization': 'Bearer ' + token}
        self.transport = transport
        self.lock = threading.Lock()
        self.timings, self.errors = defaultdict(list), Counter()
        self.successes = 0

    def call(self, operation, method, url, body=None, headers=None):
        started = time.monotonic()
        try:
            return self.transport(method, url, body, headers, local=self.local)
        finally:
            with self.lock:
                self.timings[operation].append(time.monotonic() - started)

    def workflow(self, _):
        started = time.monotonic()
        try:
            key = str(uuid.uuid4())
            body = {'customerId': str(uuid.uuid4()), 'customerReference': 'load-' + key,
                    'totalAmount': 12, 'currency': 'USD'}
            headers = {**self.auth, 'Idempotency-Key': key}
            order = self.call('create', 'POST', self.api, body, headers)
            retry = self.call('create_retry', 'POST', self.api, body, headers)
            if retry['id'] != order['id']:
                raise ValueError('Duplicate order')
            base = self.api + '/' + order['id']
            registered = self.call('register', 'POST', base + '/documents',
                                   {'fileName': 'load.pdf', 'contentType': 'application/pdf'},
                                   {**self.auth, 'Idempotency-Key': str(uuid.uuid4())})
            upload = registered['upload']
            self.call('upload', upload['method'], upload['url'], self.content, upload['headers'])
            document = base + '/documents/' + registered['document']['id']
            accepted = time.monotonic()
            self.call('complete', 'POST', document + '/complete', headers=self.auth)
            deadline = accepted + self.timeout
            while True:
                if time.monotonic() >= deadline:
                    raise TimeoutError('Processing deadline')
                result = self.call('poll', 'GET', document, headers=self.auth)
                if result['status'] in ('PROCESSED', 'FAILED'):
                    break
                time.sleep(0.5)
            checksum = hashlib.sha256(self.content).hexdigest()
            if result['status'] != 'PROCESSED' or result['sha256'] != checksum:
                raise ValueError('Incorrect document result')
            with self.lock:
                self.timings['completion_to_terminal'].append(time.monotonic() - accepted)
            download = self.call('report_authorization', 'GET', document + '/report', headers=self.auth)
            report = self.call('report', download['method'], download['url'], headers=download['headers'])
            if report['sha256'] != checksum:
                raise ValueError('Incorrect canonical report')
            with self.lock:
                self.successes += 1
        except Exception as failure:
            # Exception text can include signed URLs, bearer tokens, or response data.
            category = 'HTTP_' + str(failure.code) if isinstance(failure, HTTPError) else type(failure).__name__
            with self.lock:
                self.errors[category] += 1
        finally:
            with self.lock:
                self.timings['workflow'].append(time.monotonic() - started)


def bounded(low, high):
    def parse(value):
        number = int(value)
        if not low <= number <= high:
            raise argparse.ArgumentTypeError(f'Expected {low}..{high}')
        return number
    return parse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--local', action='store_true', help='Use only the local Compose API and token issuer')
    parser.add_argument('--api', help='HTTPS orders collection URL in an approved test environment')
    parser.add_argument('--token-file', type=Path, help='File containing a short-lived test tenant bearer token')
    parser.add_argument('--count', type=bounded(1, 1000), default=20)
    parser.add_argument('--concurrency', type=bounded(1, 16), default=2)
    parser.add_argument('--bytes', type=bounded(64, 26214400), default=65536)
    parser.add_argument('--timeout', type=bounded(1, 300), default=90)
    args = parser.parse_args()
    if args.local:
        if args.api or args.token_file:
            parser.error('--local cannot be combined with remote configuration')
        api = 'http://localhost:8080/api/v1/orders'
        token = http('POST', 'http://localhost:9000/token', local=True)['access_token']
    else:
        if not args.api or not args.token_file:
            parser.error('Specify --local or both --api and --token-file')
        api = safe_url(args.api).rstrip('/')
        token = args.token_file.read_text().strip()
        if not token or '\n' in token or '\r' in token:
            raise ValueError('Invalid token file')
    content = b'%PDF-1.7\n' + b'x' * (args.bytes - 15) + b'\n%%EOF'
    workload = Workload(api, token, content, args.timeout, args.local)
    started = time.monotonic()
    with ThreadPoolExecutor(max_workers=args.concurrency) as workers:
        list(workers.map(workload.workflow, range(args.count)))
    elapsed = time.monotonic() - started
    print(json.dumps({'mode': 'local-emulators' if args.local else 'configured-environment',
        'attempted': args.count, 'succeeded': workload.successes, 'errors': dict(workload.errors),
        'concurrency': args.concurrency, 'document_bytes': len(content), 'elapsed_seconds': round(elapsed, 3),
        'successful_workflows_per_second': round(workload.successes / elapsed, 3),
        'latency': {name: {'samples': len(values), **percentiles(values)} for name, values in workload.timings.items()}}, indent=2))
    return 0 if workload.successes == args.count else 1


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as failure:
        print(json.dumps({'setup_error': type(failure).__name__}))
        raise SystemExit(1) from None
