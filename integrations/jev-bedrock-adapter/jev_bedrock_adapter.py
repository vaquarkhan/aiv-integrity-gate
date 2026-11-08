# Copyright 2026 Vaquar Khan
# SPDX-License-Identifier: Apache-2.0
"""Local System One compatible endpoint for the AIV `jev` gate, answered by a model on Amazon Bedrock.

The jev gate POSTs {model, state: {diff, changed_files, pr_title, pr_body}, questions: {id: {type: noul,
instructions, criteria}}} to <base_url>/v1/systemone and reads answers.<id>.noul as P(yes). This adapter
asks one Bedrock Converse call per request for all questions and returns those probabilities.

    AWS_PROFILE=<profile> python integrations/jev-bedrock-adapter/jev_bedrock_adapter.py \
        --model us.anthropic.claude-sonnet-4-5-20250929-v1:0 --port 8787

Then configure the gate with `base_url: http://127.0.0.1:8787` and `api_key_env: ""`.

Security: binds to 127.0.0.1 only and has no authentication, so it must not be exposed beyond the local
machine. Request content (diff, title, body) is sent to Bedrock in the AWS account of the active profile.
"""
import argparse
import hmac
import json
import os
import random
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

MAX_REQUEST_BYTES = 2 * 1024 * 1024
SOCKET_TIMEOUT_SECONDS = 30

SYSTEM = (
    "You review pull requests for an open source project. Answer each question about the pull request with "
    "your probability (0.0 to 1.0) that the correct answer is yes. Judge only the evidence in the title, "
    "description, file list and diff. Do not guess who or what wrote the change; that is not a question. "
    "Use probabilities near 0.5 only when the evidence is genuinely unclear. Reply with one JSON object and "
    "nothing else: {\"<question id>\": {\"p_yes\": <number>, \"why\": \"<at most 20 words>\"}, ...}."
)


def build_prompt(req):
    st = req.get("state") or {}
    qs = req.get("questions") or {}
    lines = [f"Title: {st.get('pr_title') or ''}", "", "Description:", (st.get("pr_body") or "(empty)")[:8000], "",
             "Changed files:", "\n".join((st.get("changed_files") or [])[:300]), "", "Diff:", st.get("diff") or "", "",
             "Questions:"]
    for qid, q in qs.items():
        crit = q.get("criteria") or {}
        extra = "".join(f" Yes means: {crit['true']}." if "true" in crit else "") + \
            "".join(f" No means: {crit['false']}." if "false" in crit else "")
        lines.append(f"- {qid}: {q.get('instructions')}{extra}")
    return "\n".join(lines)


def parse_answers(text, ids):
    m = re.search(r"\{.*\}", text, re.S)
    data = json.loads(m.group(0)) if m else {}
    out = {}
    for qid in ids:
        v = data.get(qid)
        p = v.get("p_yes") if isinstance(v, dict) else v
        if isinstance(p, (int, float)):
            out[qid] = {"noul": max(0.0, min(1.0, float(p))), "why": (v.get("why") if isinstance(v, dict) else None)}
    return out


class Adapter:
    def __init__(self, model, region, max_tokens, connect_timeout_seconds=5,
                 request_timeout_seconds=20, retry_max_attempts=1):
        self.model = model
        self.region = region
        self.max_tokens = max_tokens
        self.connect_timeout_seconds = connect_timeout_seconds
        self.request_timeout_seconds = request_timeout_seconds
        self.retry_max_attempts = retry_max_attempts
        self.lock = threading.Lock()
        self.tokens_in = 0
        self.tokens_out = 0
        self.calls = 0

    def _client(self, remaining_seconds):
        """Build a client whose one Bedrock request cannot outlive the caller deadline."""
        return boto3.client(
            "bedrock-runtime",
            region_name=self.region,
            config=Config(
                connect_timeout=min(self.connect_timeout_seconds, remaining_seconds),
                read_timeout=remaining_seconds,
                # The adapter owns retries. Avoid a second SDK retry loop extending the deadline.
                retries={"total_max_attempts": 1, "mode": "standard"},
            ),
        )

    @staticmethod
    def _deadline_monotonic(deadline_epoch_ms):
        if deadline_epoch_ms is None:
            return time.monotonic() + 25
        try:
            remaining = (int(deadline_epoch_ms) / 1000.0) - time.time()
        except (TypeError, ValueError):
            raise ValueError("X-AIV-Deadline-Epoch-Ms must be an integer")
        if remaining <= 0:
            raise TimeoutError("AIV request deadline already expired")
        return time.monotonic() + remaining

    def answer(self, req, deadline_epoch_ms=None):
        ids = list((req.get("questions") or {}).keys())
        prompt = build_prompt(req)
        deadline = self._deadline_monotonic(deadline_epoch_ms)
        for attempt in range(self.retry_max_attempts):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("AIV request deadline expired before Bedrock response")
            # Keep a small margin for JSON serialization and the loopback HTTP response.
            request_timeout = max(1, min(self.request_timeout_seconds, int(remaining) - 1))
            if request_timeout < 1:
                raise TimeoutError("AIV request deadline has no time remaining for Bedrock")
            try:
                resp = self._client(request_timeout).converse(
                    modelId=self.model, system=[{"text": SYSTEM}],
                    messages=[{"role": "user", "content": [{"text": prompt}]}],
                    inferenceConfig={"maxTokens": self.max_tokens, "temperature": 0.0})
                break
            except ClientError as e:
                code = e.response.get("Error", {}).get("Code", "")
                retryable = code in ("ThrottlingException", "ServiceUnavailableException", "ModelNotReadyException")
                if not retryable or attempt + 1 >= self.retry_max_attempts:
                    raise
                delay = min(2 ** attempt + random.random(), max(0, deadline - time.monotonic() - 1))
                if delay <= 0:
                    raise TimeoutError("AIV request deadline expired before Bedrock retry") from e
                time.sleep(delay)
        text = "".join(c.get("text", "") for c in resp["output"]["message"]["content"])
        usage = resp.get("usage") or {}
        with self.lock:
            self.calls += 1
            self.tokens_in += usage.get("inputTokens", 0)
            self.tokens_out += usage.get("outputTokens", 0)
        return {"model": self.model, "answers": parse_answers(text, ids),
                "usage": {"input_tokens": usage.get("inputTokens", 0), "output_tokens": usage.get("outputTokens", 0)}}


def make_handler(adapter, expected_hosts, api_key=None, max_request_bytes=MAX_REQUEST_BYTES):
    """Build a loopback-only handler with bounded input and optional bearer authentication."""
    class Handler(BaseHTTPRequestHandler):
        timeout = SOCKET_TIMEOUT_SECONDS

        def log_message(self, *args):
            pass

        def _send(self, status, obj):
            body = json.dumps(obj).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _trusted_host(self):
            return self.headers.get("Host", "").lower() in expected_hosts

        def _authorized(self):
            if api_key is None:
                return True
            supplied = self.headers.get("Authorization", "")
            return hmac.compare_digest(supplied, "Bearer " + api_key)

        def _guard(self, needs_json=False):
            if not self._trusted_host():
                self._send(403, {"error": "forbidden host"})
                return False
            if not self._authorized():
                self._send(401, {"error": "unauthorized"})
                return False
            content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
            if needs_json and content_type != "application/json":
                self._send(415, {"error": "Content-Type must be application/json"})
                return False
            return True

        def do_GET(self):
            if not self._guard():
                return
            if self.path == "/stats":
                self._send(200, {"calls": adapter.calls, "input_tokens": adapter.tokens_in,
                                 "output_tokens": adapter.tokens_out, "model": adapter.model})
            else:
                self._send(404, {"error": "not found"})

        def do_POST(self):
            if not self._guard(needs_json=True):
                return
            if self.path != "/v1/systemone":
                self._send(404, {"error": "not found"})
                return
            try:
                try:
                    n = int(self.headers.get("Content-Length", ""))
                except ValueError:
                    self._send(400, {"error": "Content-Length must be an integer"})
                    return
                if n <= 0:
                    self._send(400, {"error": "Content-Length must be positive"})
                    return
                if n > max_request_bytes:
                    self._send(413, {"error": "request body too large"})
                    return
                req = json.loads(self.rfile.read(n).decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                self._send(400, {"error": "request body must be valid UTF-8 JSON"})
                return
            try:
                self._send(200, adapter.answer(req, self.headers.get("X-AIV-Deadline-Epoch-Ms")))
            except Exception as e:  # Report the error class, never AWS response details or resource identities.
                self._send(502, {"error": type(e).__name__})
    return Handler


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="us.anthropic.claude-sonnet-4-5-20250929-v1:0")
    ap.add_argument("--region", default="us-east-1")
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--max-tokens", type=int, default=1200)
    ap.add_argument("--connect-timeout-seconds", type=int, default=5)
    ap.add_argument("--request-timeout-seconds", type=int, default=20)
    ap.add_argument("--retry-max-attempts", type=int, default=1)
    ap.add_argument("--max-request-bytes", type=int, default=MAX_REQUEST_BYTES,
                    help="maximum loopback JSON request size (default: 2 MiB)")
    ap.add_argument("--api-key-env", default="",
                    help="optional environment variable containing a bearer token required by the loopback server")
    a = ap.parse_args()
    if a.connect_timeout_seconds < 1 or a.request_timeout_seconds < 2 or a.retry_max_attempts < 1:
        ap.error("timeout values must be positive; request timeout must be at least 2 seconds")
    if not 1 <= a.max_request_bytes <= 16 * 1024 * 1024:
        ap.error("max-request-bytes must be between 1 and 16777216")
    api_key = None
    if a.api_key_env:
        api_key = os.environ.get(a.api_key_env)
        if not api_key:
            ap.error("api-key-env is set but the named environment variable is empty or absent")
    adapter = Adapter(a.model, a.region, a.max_tokens, a.connect_timeout_seconds,
                      a.request_timeout_seconds, a.retry_max_attempts)
    expected_hosts = {f"127.0.0.1:{a.port}", f"localhost:{a.port}"}
    server = ThreadingHTTPServer(("127.0.0.1", a.port),
                                 make_handler(adapter, expected_hosts, api_key, a.max_request_bytes))
    print(f"jev-bedrock-adapter on http://127.0.0.1:{a.port} using {a.model}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
