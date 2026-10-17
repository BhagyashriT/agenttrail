#!/usr/bin/env python3
"""AgentTrail demo: simulate a realistic AI debugging session.

Drives the Python SDK through a full session lifecycle —
session_start -> user message -> tool calls -> error -> file_change fix ->
subagent handoff -> cost events -> session_end — so you can watch events land
on the dashboard, and so the script doubles as executable SDK documentation.

Usage:
    python demo/simulate_session.py --stub     # self-contained: spins up an
                                               # in-process stub API server
    python demo/simulate_session.py            # posts to AGENTTRAIL_URL
                                               # (default http://localhost:8080)
"""

import argparse
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "sdks", "python"))
from agenttrail import Tracer  # noqa: E402

RECEIVED = []


class StubHandler(BaseHTTPRequestHandler):
    def _send(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        n = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(n) if n else b""
        return json.loads(raw.decode()) if raw else None

    def _log(self, body):
        RECEIVED.append({"method": self.command, "path": self.path, "body": body})

    def do_POST(self):
        body = self._body()
        self._log(body)
        if self.path == "/api/v1/sessions":
            self._send(201, {"id": "demo-session-1"})
        elif self.path.endswith("/events"):
            self._send(201, {"id": body.get("id"), "seq": len(RECEIVED)})
        elif self.path.endswith(":batch"):
            self._send(200, {"results": [
                {"id": e.get("id"), "status": "created", "seq": i + 1}
                for i, e in enumerate(body["events"])]})

    def do_PATCH(self):
        body = self._body()
        self._log(body)
        self._send(200, {"id": "demo-session-1"})

    def log_message(self, *args):
        pass


def run_demo(tracer):
    """A realistic debugging session, narrated as it happens."""
    print("== starting session ==")
    tracer.start_session(
        agent_type="custom",
        agent_version="0.1.0",
        model="demo-model",
        cwd="/home/dev/myapp",
        summary="Debug flaky checkout integration test",
        metadata={"demo": True},
    )

    print("== user message ==")
    tracer.message("user", "The checkout integration test is flaky — it failed twice on CI today.")

    print("== tool: reproduce the failure ==")
    with tracer.tool("Bash",
                     summary="ran `pytest tests/test_checkout.py -x` (exit 1, 12.3s)",
                     detail={"command": "pytest tests/test_checkout.py -x",
                             "exitCode": 1,
                             "outputHead": "FAILED tests/test_checkout.py::test_total - NullPointerException"}):
        time.sleep(0.4)  # stand-in for real work; duration is timed automatically

    print("== tool: read the stack trace ==")
    with tracer.tool("Read", summary="read tests/test_checkout.py",
                     detail={"path": "tests/test_checkout.py"}):
        time.sleep(0.1)

    print("== tool: search for the culprit ==")
    with tracer.tool("Grep", summary="searched for `calculate_total`",
                     detail={"pattern": "calculate_total", "matches": 3}):
        time.sleep(0.15)

    print("== error: the failure, recorded ==")
    tracer.error(
        "Bash",
        "test_total failed: NullPointerException when cart total is None",
        {"excerpt": "NullPointerException at checkout.py:42 in calculate_total",
         "exitCode": 1},
        tags=["test-failure"],
    )

    print("== fix: edit the source ==")
    with tracer.tool("Edit", summary="edited src/checkout.py",
                     detail={"path": "src/checkout.py", "linesChanged": 2}):
        time.sleep(0.1)
    tracer.file_change("src/checkout.py", action="edit",
                       summary="guarded None total in calculate_total")

    print("== tool: re-run the tests ==")
    with tracer.tool("Bash",
                     summary="ran `pytest tests/test_checkout.py -q` (exit 0, 8.7s)",
                     detail={"command": "pytest tests/test_checkout.py -q",
                             "exitCode": 0, "outputHead": "12 passed in 8.71s"}):
        time.sleep(0.3)

    print("== handoff: full suite to a subagent ==")
    tracer.handoff("test-runner", "handed the full test suite to the test-runner subagent")

    print("== cost accounting ==")
    tracer.cost(tokens_in=42000, tokens_out=18000, cost_usd=0.21)

    print("== end session ==")
    tracer.end_session("completed", summary="Fixed None-total NPE; all 12 checkout tests green")


def report_stub():
    events = []
    for r in RECEIVED:
        if r["path"].endswith(":batch"):
            events.extend(r["body"]["events"])
        elif r["method"] == "POST" and r["path"].endswith("/events"):
            events.append(r["body"])
    print("\nStub server received %d requests, %d events:" % (len(RECEIVED), len(events)))
    for e in events:
        print("  - %-12s %-18s %s" % (e["type"], e.get("actor"), e.get("summary", "")[:70]))
    patches = [r for r in RECEIVED if r["method"] == "PATCH"]
    print("Session PATCHed to:", patches[0]["body"]["status"] if patches else "MISSING")
    assert events and events[0]["type"] == "session_start"
    assert events[-1]["type"] == "session_end"
    assert patches and patches[0]["body"]["status"] == "completed"
    print("\nDemo OK: full session lifecycle posted against the stub server.")


def main():
    parser = argparse.ArgumentParser(description="Simulate an AgentTrail debugging session.")
    parser.add_argument("--stub", action="store_true",
                        help="run against an in-process stub API server")
    args = parser.parse_args()

    if args.stub:
        server = HTTPServer(("127.0.0.1", 0), StubHandler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        url = "http://127.0.0.1:%d" % server.server_port
        print("Stub AgentTrail API at %s\n" % url)
        tracer = Tracer(server_url=url, api_key="demo-key", flush_interval=0.2)
    else:
        tracer = Tracer()  # AGENTTRAIL_URL / AGENTTRAIL_API_KEY

    try:
        run_demo(tracer)
    finally:
        tracer.close()

    if args.stub:
        report_stub()


if __name__ == "__main__":
    main()
