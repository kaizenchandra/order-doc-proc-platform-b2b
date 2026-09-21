"""Development-only issuer and emulator push bridge. Never deploy this helper to a cloud environment."""
import base64
import json
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa

ISSUER = "http://localhost:9000"
PROJECT = "local-platform"
EMAIL = "local-push@local-platform.invalid"
TENANT = "11111111-1111-4111-8111-111111111111"
KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
KEY_ID = str(uuid.uuid4())
SLOTS = threading.BoundedSemaphore(16)
ROUTES = {
    "/push/documents": ("http://document-service:8080/internal/pubsub/document-requests", "local-documents"),
    "/push/notifications": ("http://notification-service:8080/internal/pubsub/events", "local-notifications"),
}


def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()


def integer(value):
    return b64(value.to_bytes((value.bit_length() + 7) // 8, "big"))


def token(audience, push=False):
    now = int(time.time())
    claims = {"iss": ISSUER, "sub": "local-push" if push else "local-developer", "aud": audience,
              "iat": now, "exp": now + 300}
    if push:
        claims.update(email=EMAIL, email_verified=True)
    else:
        claims.update(tenant_id=TENANT, scope="orders:read orders:write")
    encode = lambda value: b64(json.dumps(value, separators=(",", ":")).encode())
    data = encode({"alg": "RS256", "typ": "JWT", "kid": KEY_ID}) + "." + encode(claims)
    return data + "." + b64(KEY.sign(data.encode(), padding.PKCS1v15(), hashes.SHA256()))


def provision(method, url, body):
    request = Request(url, json.dumps(body).encode(), {"Content-Type": "application/json"}, method=method)
    try:
        with urlopen(request, timeout=5) as response:
            response.read()
    except HTTPError as failure:
        if failure.code != 409:  # Existing resources are intentionally preserved.
            raise


def initialize():
    base = "http://pubsub:8085/v1/projects/" + PROJECT
    deadline = time.monotonic() + 120
    while True:
        try:
            for topic in ("order-events", "document-requests", "document-results"):
                provision("PUT", base + "/topics/" + topic, {})
            subscriptions = (
                ("order-document-results", "document-results", None),
                ("document-requests", "document-requests", "/push/documents"),
                ("notification-orders", "order-events", "/push/notifications"),
                ("notification-results", "document-results", "/push/notifications"),
            )
            for name, topic, push in subscriptions:
                body = {"topic": f"projects/{PROJECT}/topics/{topic}", "ackDeadlineSeconds": 60}
                if push:
                    body["pushConfig"] = {"pushEndpoint": "http://support:9000" + push}
                provision("PUT", base + "/subscriptions/" + name, body)
            for bucket in ("local-uploads", "local-reports"):
                provision("POST", "http://gcs:4443/storage/v1/b?project=" + PROJECT, {"name": bucket})
            return
        except (OSError, URLError):
            if time.monotonic() >= deadline:
                raise RuntimeError("Emulators did not become ready within 120 seconds") from None
            time.sleep(1)


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(90)

    def log_message(self, *_):
        pass  # Do not write tokens or signed URLs to request logs.

    def respond(self, status, value=None):
        data = json.dumps(value).encode() if value is not None else b""
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            self.respond(200, {"status": "ready"})
        elif self.path == "/jwks":
            key = KEY.public_key().public_numbers()
            self.respond(200, {"keys": [{"kty": "RSA", "use": "sig", "alg": "RS256", "kid": KEY_ID,
                                         "n": integer(key.n), "e": integer(key.e)}]})
        else:
            self.respond(404)

    def do_POST(self):
        if self.path == "/token":
            self.respond(200, {"access_token": token("local-orders"), "expires_in": 300, "tenant_id": TENANT})
            return
        if self.path not in ROUTES:
            self.respond(404)
            return
        if not SLOTS.acquire(blocking=False):
            self.respond(503)
            return
        try:
            try:
                size = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                self.respond(400)
                return
            if size <= 0 or size > 96 * 1024 or self.headers.get("Transfer-Encoding"):
                self.respond(413)
                return
            data = self.rfile.read(size)
            if len(data) != size:
                self.respond(400)
                return
            destination, audience = ROUTES[self.path]
            request = Request(destination, data, {"Content-Type": "application/json",
                              "Authorization": "Bearer " + token(audience, push=True)}, method="POST")
            with urlopen(request, timeout=50) as response:
                self.respond(204 if response.status in (200, 201, 202, 204) else 503)
        except (OSError, URLError):
            # No ACK before downstream commit. A timeout may have committed; inbox/report dedup handles retry.
            self.respond(503)
        finally:
            SLOTS.release()


if __name__ == "__main__":
    initialize()
    print("Local resources initialized; development issuer and push bridge ready", flush=True)
    ThreadingHTTPServer(("0.0.0.0", 9000), Handler).serve_forever()
