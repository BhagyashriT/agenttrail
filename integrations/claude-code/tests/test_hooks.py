"""Tests for the Claude Code AgentTrail hooks.

Each test feeds a sample Claude Code hook JSON payload to a hook script via
stdin, with a stub HTTP server standing in for the AgentTrail API, and asserts
the POST payloads match the API contract. HOME is redirected to a tmp dir so
~/.agenttrail/state.json and hooks.log stay isolated.
"""

import json
import os
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

HOOKS_DIR = os.path.join(os.path.dirname(__file__), "..", "hooks")
API_KEY = "test-key-123"


class StubHandler(BaseHTTPRequestHandler):
    requests = []  # class-level log, reset per test
    session_counter = 0
    event_counter = 0

    def _read_json(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b""
        return json.loads(raw.decode("utf-8")) if raw else None

    def _send(self, status, body):
        data = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _record(self, body):
        StubHandler.requests.append(
            {
                "method": self.command,
                "path": self.path.split("?")[0],
                "api_key": self.headers.get("X-API-Key"),
                "body": body,
            }
        )

    def do_POST(self):
        body = self._read_json()
        self._record(body)
        path = self.path.split("?")[0]
        if path == "/api/v1/sessions":
            StubHandler.session_counter += 1
            self._send(201, {"id": "trail-sess-%d" % StubHandler.session_counter})
        elif path.endswith("/events"):
            StubHandler.event_counter += 1
            self._send(201, {"id": "trail-evt-%d" % StubHandler.event_counter,
                             "seq": StubHandler.event_counter})
        else:
            self._send(404, {"error": "not_found"})

    def do_PATCH(self):
        body = self._read_json()
        self._record(body)
        self._send(200, {"id": "trail-sess-1", "status": body.get("status")})

    def log_message(self, *args):
        pass


@pytest.fixture()
def stub():
    StubHandler.requests = []
    StubHandler.session_counter = 0
    StubHandler.event_counter = 0
    server = HTTPServer(("127.0.0.1", 0), StubHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield "http://127.0.0.1:%d" % server.server_port
    server.shutdown()
    thread.join()


@pytest.fixture()
def home(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    return tmp_path


def run_hook(script, payload, stub_url, home, extra_env=None):
    env = {
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "HOME": str(home),
        "AGENTTRAIL_URL": stub_url,
        "AGENTTRAIL_API_KEY": API_KEY,
    }
    if extra_env:
        env.update(extra_env)
    proc = subprocess.run(
        [sys.executable, os.path.join(HOOKS_DIR, script)],
        input=json.dumps(payload),
        capture_output=True,
        text=True,
        env=env,
        timeout=30,
    )
    return proc


def events_of_type(reqs, type_):
    return [r for r in reqs if r["path"].endswith("/events") and r["body"].get("type") == type_]


def test_session_start_creates_session_and_caches_mapping(stub, home):
    proc = run_hook("session_start.py", {
        "session_id": "claude-abc",
        "cwd": "/home/user/proj",
        "version": "2.0.0",
    }, stub, home)
    assert proc.returncode == 0, proc.stderr

    reqs = StubHandler.requests
    creates = [r for r in reqs if r["path"] == "/api/v1/sessions"]
    assert len(creates) == 1
    assert creates[0]["api_key"] == API_KEY
    body = creates[0]["body"]
    assert body["agentType"] == "claude-code"
    assert body["cwd"] == "/home/user/proj"
    assert body["clientSessionId"]  # idempotent creation key present

    state = json.loads((home / ".agenttrail" / "state.json").read_text())
    assert state["sessions"]["claude-abc"] == "trail-sess-1"

    # Idempotent: second start reuses the cached mapping, no new POST.
    StubHandler.requests = []
    run_hook("session_start.py", {"session_id": "claude-abc"}, stub, home)
    assert [r for r in StubHandler.requests if r["path"] == "/api/v1/sessions"] == []


def test_user_prompt_submit_posts_user_message(stub, home):
    run_hook("session_start.py", {"session_id": "claude-abc"}, stub, home)
    StubHandler.requests = []
    proc = run_hook("user_prompt_submit.py", {
        "session_id": "claude-abc",
        "prompt": "Fix the flaky checkout test",
    }, stub, home)
    assert proc.returncode == 0, proc.stderr

    msgs = events_of_type(StubHandler.requests, "message")
    assert len(msgs) == 1
    body = msgs[0]["body"]
    assert body["actor"] == "user"
    assert "Fix the flaky checkout test" in body["summary"]
    assert body["detail"]["prompt"] == "Fix the flaky checkout test"
    assert body["ts"].endswith("Z")


def test_pre_and_post_tool_use_post_single_tool_call(stub, home):
    run_hook("session_start.py", {"session_id": "claude-abc"}, stub, home)
    run_hook("pre_tool_use.py", {
        "session_id": "claude-abc",
        "tool_name": "Bash",
        "tool_input": {"command": "npm test"},
    }, stub, home)
    StubHandler.requests = []
    proc = run_hook("post_tool_use.py", {
        "session_id": "claude-abc",
        "tool_name": "Bash",
        "tool_input": {"command": "npm test"},
        "tool_response": {"stdout": "ok\n", "stderr": "", "exitCode": 0},
    }, stub, home)
    assert proc.returncode == 0, proc.stderr

    calls = events_of_type(StubHandler.requests, "tool_call")
    assert len(calls) == 1
    body = calls[0]["body"]
    assert body["name"] == "Bash"
    assert body["actor"] == "agent"
    assert "npm test" in body["summary"]
    assert "exit 0" in body["summary"]
    assert isinstance(body["durationMs"], int) and body["durationMs"] >= 0
    assert body["detail"]["exitCode"] == 0
    assert body["detail"]["outputHead"] == "ok\n"
    assert len(body["summary"]) <= 280
    # No error event on success.
    assert events_of_type(StubHandler.requests, "error") == []


def test_failed_tool_posts_tool_call_and_error_event(stub, home):
    run_hook("session_start.py", {"session_id": "claude-abc"}, stub, home)
    run_hook("pre_tool_use.py", {
        "session_id": "claude-abc",
        "tool_name": "Bash",
        "tool_input": {"command": "npm test"},
    }, stub, home)
    StubHandler.requests = []
    run_hook("post_tool_use.py", {
        "session_id": "claude-abc",
        "tool_name": "Bash",
        "tool_input": {"command": "npm test"},
        "tool_response": {"stdout": "", "stderr": "FAIL: 3 tests failed\nAssertionError", "exitCode": 1},
    }, stub, home)

    calls = events_of_type(StubHandler.requests, "tool_call")
    assert len(calls) == 1
    assert "exit 1" in calls[0]["body"]["summary"]

    errors = events_of_type(StubHandler.requests, "error")
    assert len(errors) == 1
    body = errors[0]["body"]
    assert body["name"] == "Bash"
    assert "failed" in body["summary"]
    assert "FAIL: 3 tests failed" in body["detail"]["excerpt"]


def test_output_truncated_to_2000_chars(stub, home):
    run_hook("session_start.py", {"session_id": "claude-abc"}, stub, home)
    run_hook("pre_tool_use.py", {"session_id": "claude-abc", "tool_name": "Bash",
                                 "tool_input": {"command": "cat big.log"}}, stub, home)
    StubHandler.requests = []
    run_hook("post_tool_use.py", {
        "session_id": "claude-abc",
        "tool_name": "Bash",
        "tool_input": {"command": "cat big.log"},
        "tool_response": {"stdout": "x" * 5000, "exitCode": 0},
    }, stub, home)
    calls = events_of_type(StubHandler.requests, "tool_call")
    assert len(calls[0]["body"]["detail"]["outputHead"]) <= 2020  # 2000 + marker


def test_subagent_stop_posts_handoff(stub, home):
    run_hook("session_start.py", {"session_id": "claude-abc"}, stub, home)
    StubHandler.requests = []
    proc = run_hook("subagent_stop.py", {
        "session_id": "claude-abc",
        "agent_name": "test-runner",
    }, stub, home)
    assert proc.returncode == 0, proc.stderr

    handoffs = events_of_type(StubHandler.requests, "handoff")
    assert len(handoffs) == 1
    assert handoffs[0]["body"]["actor"] == "subagent:test-runner"


def test_stop_posts_session_end_and_patches_completed(stub, home):
    run_hook("session_start.py", {"session_id": "claude-abc"}, stub, home)
    StubHandler.requests = []
    proc = run_hook("stop.py", {"session_id": "claude-abc"}, stub, home)
    assert proc.returncode == 0, proc.stderr

    ends = events_of_type(StubHandler.requests, "session_end")
    assert len(ends) == 1

    patches = [r for r in StubHandler.requests if r["method"] == "PATCH"]
    assert len(patches) == 1
    assert patches[0]["body"]["status"] == "completed"

    state = json.loads((home / ".agenttrail" / "state.json").read_text())
    assert "claude-abc" not in state["sessions"]


def test_notification_posts_system_message(stub, home):
    run_hook("session_start.py", {"session_id": "claude-abc"}, stub, home)
    StubHandler.requests = []
    proc = run_hook("notification.py", {
        "session_id": "claude-abc",
        "message": "Approval required: run rm -rf /tmp/build",
    }, stub, home)
    assert proc.returncode == 0, proc.stderr

    msgs = events_of_type(StubHandler.requests, "message")
    assert len(msgs) == 1
    assert msgs[0]["body"]["actor"] == "system"
    assert "Approval required" in msgs[0]["body"]["summary"]


def test_hook_never_breaks_session_and_never_logs_key(tmp_path):
    # Point at a dead port: every HTTP call fails. Hook must still exit 0
    # and log exactly one line that does not contain the API key.
    dead_url = "http://127.0.0.1:1"
    proc = run_hook("user_prompt_submit.py",
                    {"session_id": "x", "prompt": "hi"},
                    dead_url, tmp_path)
    assert proc.returncode == 0
    assert proc.stdout == ""
    log_path = tmp_path / ".agenttrail" / "hooks.log"
    assert log_path.exists()
    lines = log_path.read_text().strip().splitlines()
    assert len(lines) == 1
    assert API_KEY not in log_path.read_text()
