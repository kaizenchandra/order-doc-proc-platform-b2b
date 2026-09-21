"""Behavioral tests against the running local Compose stack; creates only uniquely identified test records."""
import base64
import json
import subprocess
import unittest
import uuid
from urllib.error import HTTPError
from urllib.parse import quote

from smoke import API, ROOT, request as http_request, wait_for

PUBSUB = "http://localhost:8085/v1/projects/local-platform"


def request(*args, **kwargs):
    try:
        return http_request(*args, **kwargs)
    except HTTPError as failure:
        # Do not let a unittest traceback expose a signed upload/download URL.
        raise RuntimeError("Local HTTP request failed with status " + str(failure.code)) from None


def sql(database, statement):
    users = {"order-db": "orders", "notification-db": "notifications"}
    user = users[database]
    result = subprocess.run(["docker", "compose", "exec", "-T", database, "psql", "-U", user, "-d", user,
                             "-v", "ON_ERROR_STOP=1", "-tAc", statement], cwd=ROOT, capture_output=True, text=True,
                            check=True, timeout=15)
    return result.stdout.strip()


class LocalWorkflowTests(unittest.TestCase):
    def setUp(self):
        identity = request("POST", "http://localhost:9000/token")
        self.auth = {"Authorization": "Bearer " + identity["access_token"]}
        self.tenant = str(uuid.UUID(identity["tenant_id"]))

    def create(self, amount="12.00"):
        payload = {"customerId": str(uuid.uuid4()), "customerReference": "regression-" + str(uuid.uuid4()),
                   "totalAmount": amount, "currency": "USD"}
        # Send a JSON number without first converting it through a binary float.
        wire = json.dumps(payload).replace('"totalAmount": "' + amount + '"', '"totalAmount": ' + amount).encode()
        return request("POST", API, wire, {**self.auth, "Content-Type": "application/json", "Idempotency-Key": str(uuid.uuid4())})

    def process(self, content):
        order = self.create()
        base = API + "/" + order["id"]
        registration = request("POST", base + "/documents", {"fileName": "regression.pdf", "contentType": "application/pdf"},
                               {**self.auth, "Idempotency-Key": str(uuid.uuid4())})
        upload = registration["upload"]
        request(upload["method"], upload["url"], content, upload["headers"], raw=True)
        document_url = base + "/documents/" + registration["document"]["id"]
        request("POST", document_url + "/complete", headers=self.auth)

        def terminal():
            state = request("GET", document_url, headers=self.auth)
            return state if state["status"] in ("PROCESSED", "FAILED") else None

        document = wait_for(terminal, "terminal document", timeout=90)
        report = self.report(document_url)
        return order, document_url, document, report

    def report(self, document_url):
        authorization = request("GET", document_url + "/report", headers=self.auth)
        return request(authorization["method"], authorization["url"], headers=authorization["headers"])

    def notification_count(self, order, expected):
        order_id = str(uuid.UUID(order["id"]))
        statement = f"""SELECT count(*) FROM audit_records a JOIN notification_records n ON n.audit_record_id=a.id
                        WHERE a.aggregate_id='{order_id}' AND a.tenant_id='{self.tenant}' AND n.status='RECORDED'"""
        wait_for(lambda: sql("notification-db", statement) == str(expected), "independent notification commits", timeout=60)

    def test_invalid_pdf_commits_failure_report_and_notification(self):
        order, url, document, report = self.process(b"this is not a PDF")
        self.assertEqual("FAILED", document["status"])
        self.assertEqual("INVALID_PDF", document["failureCode"])
        self.assertEqual("INVALID_PDF", report["failureCode"])
        self.assertIsNone(report["sha256"])
        self.assertEqual(document["processingRequestId"], report["request"]["data"]["processingRequestId"])
        # Retrying completion must not schedule another processing attempt, including terminal failures.
        self.assertEqual(document, request("POST", url + "/complete", headers=self.auth))
        self.notification_count(order, 2)

    def test_maximum_decimal_amount_survives_outbox_publication(self):
        order = self.create("99999999999999999.99")
        self.notification_count(order, 1)
        order_id = str(uuid.UUID(order["id"]))
        self.assertEqual("99999999999999999.99", sql("order-db", f"SELECT total_amount::text FROM orders WHERE id='{order_id}'"))
        self.assertEqual("1", sql("order-db", f"SELECT count(*) FROM outbox_events WHERE aggregate_id='{order_id}' AND published_at IS NOT NULL"))

    def test_replay_after_input_loss_republishes_the_same_canonical_result(self):
        order, url, before, report = self.process(b"%PDF-1.7\nreplay fixture\n%%EOF\n")
        self.assertEqual("PROCESSED", before["status"])
        self.notification_count(order, 2)
        source = report["request"]["data"]
        probe = "regression-" + uuid.uuid4().hex
        subscription = PUBSUB + "/subscriptions/" + probe
        request("PUT", subscription, {"topic": "projects/local-platform/topics/document-results", "ackDeadlineSeconds": 10})
        self.addCleanup(lambda: request("DELETE", subscription, raw=True))
        # Delete only this test's generated upload. Recovery must use its already committed canonical report.
        request("DELETE", "http://localhost:4443/storage/v1/b/" + quote(source["bucket"], safe="") + "/o/"
                + quote(source["objectName"], safe="") + "?ifGenerationMatch=" + source["generation"], raw=True)
        encoded = base64.b64encode(json.dumps(report["request"]).encode()).decode()
        request("POST", PUBSUB + "/topics/document-requests:publish", {"messages": [{"data": encoded}, {"data": encoded}]})
        observed = {}

        def replayed():
            response = request("POST", subscription + ":pull", {"maxMessages": 10, "returnImmediately": True})
            received = response.get("receivedMessages", [])
            for item in received:
                event = json.loads(base64.b64decode(item["message"]["data"]))
                if event["aggregateId"] == order["id"]:
                    observed[item["message"]["messageId"]] = event
            if received:
                request("POST", subscription + ":acknowledge", {"ackIds": [item["ackId"] for item in received]})
            return len(observed) >= 2

        wait_for(replayed, "two replay publications after input deletion", timeout=60)
        self.assertTrue(all(event["eventId"] == report["resultEventId"] for event in observed.values()))
        self.assertTrue(all(event == next(iter(observed.values())) for event in observed.values()))
        self.assertEqual(report, self.report(url))
        self.assertEqual(before, request("GET", url, headers=self.auth))
        # Exercise notification redelivery synchronously as well, so this assertion cannot race its consumer.
        event_bytes = base64.b64encode(json.dumps(next(iter(observed.values()))).encode()).decode()
        for _ in range(2):
            request("POST", "http://localhost:9000/push/notifications", {
                "subscription": "projects/local-platform/subscriptions/notification-results",
                "message": {"messageId": uuid.uuid4().hex, "data": event_bytes}})
        self.notification_count(order, 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
