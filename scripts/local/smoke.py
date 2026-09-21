"""End-to-end local check. Uses only Python's standard library and Docker Compose."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
API = "http://localhost:8080/api/v1/orders"


def request(method, url, body=None, headers=None, raw=False):
    headers = dict(headers or {})
    if body is not None and not isinstance(body, bytes):
        body = json.dumps(body).encode()
        headers.setdefault("Content-Type", "application/json")
    with urlopen(Request(url, body, headers, method=method), timeout=20) as response:
        data = response.read()
        return data if raw else json.loads(data) if data else None


def wait_for(action, description, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            value = action()
            if value:
                return value
        except (OSError, URLError):
            pass
        time.sleep(1)
    raise RuntimeError("Timed out waiting for " + description)


def main():
    identity = wait_for(lambda: request("POST", "http://localhost:9000/token"), "local issuer")
    auth = {"Authorization": "Bearer " + identity["access_token"]}

    def api_ready():
        try:
            request("GET", API + "/" + str(uuid.uuid4()), headers=auth)
        except HTTPError as error:
            return error.code == 404
        return False

    wait_for(api_ready, "authenticated Order API")
    for endpoint in ("http://localhost:8081/internal/pubsub/document-requests",
                     "http://localhost:8082/internal/pubsub/events"):
        def protected():
            try:
                request("POST", endpoint, {})
            except HTTPError as error:
                return error.code == 401
            raise RuntimeError("Push endpoint accepted an unauthenticated request")
        wait_for(protected, "authenticated push endpoint")
    key = str(uuid.uuid4())
    order_body = {"customerId": str(uuid.uuid4()), "customerReference": "local-smoke-" + key,
                  "totalAmount": 12.00, "currency": "USD"}
    order_headers = {**auth, "Idempotency-Key": key}
    order = request("POST", API, order_body, order_headers)
    assert request("POST", API, order_body, order_headers)["id"] == order["id"], "Order retry duplicated the resource"
    base = API + "/" + order["id"]
    registered = request("POST", base + "/documents", {"fileName": "sample.pdf", "contentType": "application/pdf"},
                         {**auth, "Idempotency-Key": str(uuid.uuid4())})
    # This processor validates PDF markers and computes metadata; it does not parse a full PDF document.
    content = b"%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n"
    upload = registered["upload"]
    request(upload["method"], upload["url"], content, upload["headers"], raw=True)
    document_url = base + "/documents/" + registered["document"]["id"]
    request("POST", document_url + "/complete", headers=auth)

    def terminal():
        result = request("GET", document_url, headers=auth)
        return result if result["status"] in ("PROCESSED", "FAILED") else None

    document = wait_for(terminal, "terminal document result", timeout=90)
    assert document["status"] == "PROCESSED", "Document processing failed"
    checksum = hashlib.sha256(content).hexdigest()
    assert document["sha256"] == checksum, "Stored checksum differs"
    download = request("GET", document_url + "/report", headers=auth)
    report = request(download["method"], download["url"], headers=download["headers"])
    assert report["sha256"] == checksum, "Report checksum differs"

    # Independent fan-out must commit both notifications before the smoke check succeeds.
    order_id = str(uuid.UUID(order["id"]))
    sql = f"""SELECT count(*) FROM audit_records a JOIN notification_records n ON n.audit_record_id = a.id
              WHERE a.aggregate_id = '{order_id}' AND a.tenant_id = '{identity['tenant_id']}'
              AND a.event_type IN ('OrderCreated', 'DocumentProcessed') AND n.status = 'RECORDED'"""

    def notified():
        result = subprocess.run(["docker", "compose", "exec", "-T", "notification-db", "psql", "-U", "notifications",
                                 "-d", "notifications", "-tAc", sql], cwd=ROOT, capture_output=True, text=True, check=True)
        return result.stdout.strip() == "2"

    wait_for(notified, "both durable notification intents", timeout=60)
    print("PASS: order retry, upload, asynchronous processing, checksum, report download, and two notification intents")
    print("Order ID: " + order_id)
    print("Document ID: " + document["id"])


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        # HTTPError messages may contain signed URLs; report only safe diagnostics.
        print("Smoke check failed: " + type(failure).__name__ +
              (" (HTTP " + str(failure.code) + ")" if isinstance(failure, HTTPError) else ""), file=sys.stderr)
        print("Inspect docker compose ps and docker compose logs --tail=100", file=sys.stderr)
        sys.exit(1)
