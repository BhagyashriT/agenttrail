"""Tests for the AgentTrail Python SDK."""

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from agenttrail import AgentTrailError, Tracer


class StubHandler(BaseHTTPRequestHandler):
    requests = []

    def _read_json(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b""
        return json.loads(raw.decode("utf-8")) if raw else None

    def _send(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _record(self, body):
        StubHandler.requests.append({
            "method": self.command,
            "path": self.path.split("?")[0],
            "api_key": self.headers.get("X-API-Key"),
            "body": body,
        })

    def do_POST(self):
        body = self._read_json()
        self._record(body)
        if self.path == "/api/v1/sessions":
            self._send(201, {"id": "sess-1"})
        elif self.path.endswith("/events"):
            self._send(201, {"id": body.get("id"), "seq": 1})
        elif self.path.endswith(":batch"):
            self._send(200, {"results": [
                {"id": e.get("id"), "status": "created", "seq": i + 1}
                for i, e in enumerate(body["events"])
            ]})
        else:
            self._send(404, {"error": "not_found"})

    def do_PATCH(self):
        body = self._read_json()
        self._record(body)
        self._send(200, {"id": "sess-1"})

    def log_message(self, *args):
        pass


@pytest.fixture()
def stub_url():
    StubHandler.requests = []
    server = HTTPServer(("127.0.0.1", 0), StubHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield "http://127.0.0.1:%d" % server.server_port
    server.shutdown()
    thread.join()


def make_tracer(stub_url, **kw):
    kw.setdefault("batch_size", 1000)   # no auto-flush during assertions
    kw.setdefault("flush_interval", 3600)
    return Tracer(server_url=stub_url, api_key="k", **kw)


def batched_events():
    """All events delivered to the stub, in order (single and :batch posts)."""
    out = []
    for r in StubHandler.requests:
        if r["path"].endswith(":batch"):
            out.extend(r["body"]["events"])
        elif r["method"] == "POST" and r["path"].endswith("/events"):
            out.append(r["body"])
    return out


def test_tool_context_posts_valid_tool_call(stub_url):
    tracer = make_tracer(stub_url)
    tracer.session_id = "sess-1"  # skip start_session; focus on the tool event

    with tracer.tool("Bash", summary="ran `pytest` (exit 0, 3.1s)",
                     detail={"command": "pytest", "exitCode": 0}):
        time.sleep(0.05)

    tracer.flush()
    tracer.close()

    events = batched_events()
    assert len(events) == 1
    evt = events[0]
    assert evt["type"] == "tool_call"
    assert evt["name"] == "Bash"
    assert evt["actor"] == "agent"
    assert evt["summary"] == "ran `pytest` (exit 0, 3.1s)"
    assert len(evt["summary"]) <= 280
    assert evt["detail"] == {"command": "pytest", "exitCode": 0}
    assert isinstance(evt["durationMs"], int) and evt["durationMs"] >= 50
    assert evt["ts"].endswith("Z")
    assert evt["id"]  # client-generated id for idempotent retry


def test_tool_context_posts_error_event_on_exception(stub_url):
    tracer = make_tracer(stub_url)
    tracer.session_id = "sess-1"
    with pytest.raises(ValueError):
        with tracer.tool("Bash"):
            raise ValueError("boom")
    tracer.flush()
    tracer.close()

    events = batched_events()
    by_type = [e["type"] for e in events]
    assert by_type == ["tool_call", "error"]
    assert "failed" in events[0]["summary"]
    assert "failure" in events[0]["tags"]
    assert events[1]["name"] == "Bash"
    assert "ValueError" in events[1]["summary"]


def test_start_and_end_session_round_trip(stub_url):
    tracer = make_tracer(stub_url)
    sid = tracer.start_session(agent_type="custom", summary="demo")
    assert sid == "sess-1"
    tracer.message("user", "hello")
    tracer.cost(tokens_in=10, tokens_out=5, cost_usd=0.001)
    tracer.end_session("completed")

    posts = [r for r in StubHandler.requests if r["method"] == "POST"]
    assert posts[0]["path"] == "/api/v1/sessions"
    assert posts[0]["body"]["agentType"] == "custom"
    assert posts[0]["api_key"] == "k"

    events = batched_events()
    types = [e["type"] for e in events]
    assert types[0] == "session_start"
    assert "message" in types and "cost" in types
    assert types[-1] == "session_end"

    patches = [r for r in StubHandler.requests if r["method"] == "PATCH"]
    assert len(patches) == 1
    assert patches[0]["body"]["status"] == "completed"


def test_retries_exhausted_raises_agent_trail_error():
    tracer = make_tracer("http://127.0.0.1:1", max_retries=1, timeout=1)  # dead port
    with pytest.raises(AgentTrailError):
        tracer.start_session(agent_type="custom")
    tracer.close()
