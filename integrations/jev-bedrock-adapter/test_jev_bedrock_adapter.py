"""Unit tests for the JEV Bedrock adapter client, deadline and loopback HTTP boundary."""
import http.client
import importlib.util
import json
from pathlib import Path
import threading
import time
import unittest
from unittest.mock import patch

from http.server import ThreadingHTTPServer

SOURCE = Path(__file__).with_name("jev_bedrock_adapter.py")
SPEC = importlib.util.spec_from_file_location("jev_bedrock_adapter", SOURCE)
ADAPTER = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(ADAPTER)


class FakeClient:
    def __init__(self, response):
        self.response = response
        self.calls = []

    def converse(self, **kwargs):
        self.calls.append(kwargs)
        return self.response


class JevBedrockAdapterTest(unittest.TestCase):
    def test_parse_answers_clamps_and_omits_invalid_values(self):
        result = ADAPTER.parse_answers(
            '{"one":{"p_yes":1.5,"why":"high"},"two":-1,"three":"bad"}',
            ["one", "two", "three", "missing"],
        )
        self.assertEqual(1.0, result["one"]["noul"])
        self.assertEqual("high", result["one"]["why"])
        self.assertEqual(0.0, result["two"]["noul"])
        self.assertNotIn("three", result)
        self.assertNotIn("missing", result)

    def test_expired_deadline_is_rejected_before_bedrock_call(self):
        adapter = ADAPTER.Adapter("model", "us-east-1", 100)
        with self.assertRaisesRegex(TimeoutError, "already expired"):
            adapter.answer({"questions": {}}, str(int((time.time() - 1) * 1000)))

    def test_answer_uses_bounded_client_and_tracks_usage(self):
        adapter = ADAPTER.Adapter("model", "us-east-1", 120, request_timeout_seconds=20)
        client = FakeClient({
            "output": {"message": {"content": [{"text": '{"check":{"p_yes":0.8,"why":"evidence"}}'}]}},
            "usage": {"inputTokens": 11, "outputTokens": 7},
        })
        with patch.object(adapter, "_client", return_value=client) as factory:
            result = adapter.answer({"questions": {"check": {}}}, str(int((time.time() + 10) * 1000)))
        self.assertEqual("model", result["model"])
        self.assertEqual(0.8, result["answers"]["check"]["noul"])
        self.assertEqual(11, result["usage"]["input_tokens"])
        self.assertEqual(1, adapter.calls)
        self.assertEqual(11, adapter.tokens_in)
        self.assertEqual(7, adapter.tokens_out)
        self.assertGreaterEqual(factory.call_args.args[0], 1)
        self.assertLessEqual(factory.call_args.args[0], 9)
        self.assertEqual("model", client.calls[0]["modelId"])
        self.assertEqual(0.0, client.calls[0]["inferenceConfig"]["temperature"])

    def test_invalid_deadline_header_is_rejected(self):
        adapter = ADAPTER.Adapter("model", "us-east-1", 100)
        with self.assertRaisesRegex(ValueError, "must be an integer"):
            adapter.answer({"questions": {}}, "not-a-number")

    def server(self, adapter, api_key=None, max_request_bytes=ADAPTER.MAX_REQUEST_BYTES):
        hosts = set()
        handler = ADAPTER.make_handler(adapter, hosts, api_key, max_request_bytes)
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        port = server.server_address[1]
        hosts.update({f"127.0.0.1:{port}", f"localhost:{port}"})
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        def cleanup():
            server.shutdown()
            thread.join(2)
            server.server_close()

        self.addCleanup(cleanup)
        return port

    @staticmethod
    def request(port, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        merged = {"Host": f"127.0.0.1:{port}"}
        merged.update(headers or {})
        conn.request(method, path, body=body, headers=merged)
        response = conn.getresponse()
        data = response.read()
        status = response.status
        conn.close()
        return status, json.loads(data.decode("utf-8"))

    def test_loopback_handler_requires_expected_host_json_and_bounded_body(self):
        adapter = ADAPTER.Adapter("model", "us-east-1", 100)
        adapter.answer = unittest.mock.Mock(return_value={"model": "model", "answers": {}})
        port = self.server(adapter, max_request_bytes=1)

        status, body = self.request(port, "POST", "/v1/systemone", b"{}", {
            "Host": "example.invalid", "Content-Type": "application/json"})
        self.assertEqual((403, {"error": "forbidden host"}), (status, body))
        status, body = self.request(port, "POST", "/v1/systemone", b"{}", {"Content-Type": "text/plain"})
        self.assertEqual((415, {"error": "Content-Type must be application/json"}), (status, body))
        status, body = self.request(port, "POST", "/v1/systemone", b"{}", {"Content-Type": "application/json"})
        self.assertEqual((413, {"error": "request body too large"}), (status, body))
        adapter.answer.assert_not_called()

    def test_loopback_handler_optional_bearer_auth_and_stats(self):
        adapter = ADAPTER.Adapter("model", "us-east-1", 100)
        adapter.answer = unittest.mock.Mock(return_value={"model": "model", "answers": {}})
        port = self.server(adapter, api_key="test-token")
        status, body = self.request(port, "GET", "/stats")
        self.assertEqual((401, {"error": "unauthorized"}), (status, body))
        status, body = self.request(port, "POST", "/v1/systemone", b"{}", {"Content-Type": "application/json"})
        self.assertEqual((401, {"error": "unauthorized"}), (status, body))
        status, body = self.request(port, "POST", "/v1/systemone", b"{}", {
            "Content-Type": "application/json; charset=utf-8", "Authorization": "Bearer test-token"})
        self.assertEqual((200, {"model": "model", "answers": {}}), (status, body))
        adapter.answer.assert_called_once()
        status, body = self.request(port, "GET", "/stats", headers={"Authorization": "Bearer test-token"})
        self.assertEqual(200, status)
        self.assertEqual("model", body["model"])

    def test_loopback_handler_rejects_invalid_json_and_redacts_exception_details(self):
        adapter = ADAPTER.Adapter("model", "us-east-1", 100)
        port = self.server(adapter)
        status, body = self.request(port, "POST", "/v1/systemone", b"not-json", {"Content-Type": "application/json"})
        self.assertEqual((400, {"error": "request body must be valid UTF-8 JSON"}), (status, body))
        adapter.answer = unittest.mock.Mock(side_effect=RuntimeError(
            "AccessDenied arn:aws:sts::123456789012:assumed-role/jev-role/session"))
        status, body = self.request(port, "POST", "/v1/systemone", b"{}", {"Content-Type": "application/json"})
        self.assertEqual((502, {"error": "RuntimeError"}), (status, body))
        self.assertNotIn("123456789012", json.dumps(body))
        self.assertNotIn("arn:aws", json.dumps(body))


if __name__ == "__main__":
    unittest.main()
