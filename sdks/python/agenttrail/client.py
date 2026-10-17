"""AgentTrail Python SDK.

A small, dependency-free client for the AgentTrail v1 API
(see docs/API_CONTRACT.md). Events are batched client-side and flushed by a
background thread every ``flush_interval`` seconds or whenever ``batch_size``
events accumulate. Use ``flush()`` before exiting to avoid losing events.

Example:
    tracer = Tracer()
    tracer.start_session(agent_type="custom", summary="debug checkout flake")
    tracer.message("user", "The checkout test is flaky")
    with tracer.tool("Bash", summary="ran `pytest` (exit 0, 3.1s)"):
        subprocess.run(["pytest", "-x"], check=True)
    tracer.cost(tokens_in=1200, tokens_out=800, cost_usd=0.012)
    tracer.end_session("completed")
"""

import contextlib
import json
import os
import threading
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

TRUNC_LIMIT = 2000


class AgentTrailError(Exception):
    """Raised when the API call fails after all retries are exhausted."""


def _utcnow_iso():
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _trunc(value, limit=TRUNC_LIMIT):
    text = "" if value is None else str(value)
    return text if len(text) <= limit else text[:limit] + "...(truncated)"


class Tracer:
    """Client for the AgentTrail API with background batching."""

    def __init__(
        self,
        server_url=None,
        api_key=None,
        session_id=None,
        batch_size=50,
        flush_interval=5.0,
        max_retries=3,
        timeout=10.0,
    ):
        self.server_url = (server_url or os.environ.get("AGENTTRAIL_URL", "http://localhost:8080")).rstrip("/")
        self.api_key = api_key or os.environ.get("AGENTTRAIL_API_KEY", "dev-key")
        self.session_id = session_id
        self.batch_size = batch_size
        self.flush_interval = flush_interval
        self.max_retries = max_retries
        self.timeout = timeout

        self._queue = []
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._flusher = threading.Thread(target=self._flush_loop, daemon=True)
        self._flusher.start()

    # ------------------------------------------------------------------ HTTP
    def _request(self, method, path, payload=None):
        url = self.server_url + path
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        last_error = None
        for attempt in range(self.max_retries + 1):
            try:
                req = urllib.request.Request(
                    url,
                    data=data,
                    method=method,
                    headers={"Content-Type": "application/json", "X-API-Key": self.api_key},
                )
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    raw = resp.read().decode("utf-8")
                    return resp.status, (json.loads(raw) if raw.strip() else None)
            except Exception as e:  # noqa: BLE001 - retry then wrap
                last_error = e
                if attempt < self.max_retries:
                    time.sleep(min(2.0 ** attempt * 0.5, 8.0))
        raise AgentTrailError(
            "AgentTrail %s %s failed after %d retries: %s"
            % (method, path, self.max_retries, last_error)
        )

    def _require_session(self):
        if not self.session_id:
            raise AgentTrailError("no session: call start_session() first")

    # ---------------------------------------------------------------- batching
    def _enqueue(self, event):
        event = dict(event)
        event.setdefault("id", str(uuid.uuid4()))  # idempotent retry key
        event.setdefault("ts", _utcnow_iso())
        event.setdefault("actor", "agent")
        with self._lock:
            self._queue.append(event)
            ready = len(self._queue) >= self.batch_size
            batch = list(self._queue) if ready else None
            if ready:
                self._queue = []
        if batch:
            self._send_batch(batch)

    def _flush_loop(self):
        while not self._stop.wait(self.flush_interval):
            self.flush()

    def _send_batch(self, batch):
        self._require_session()
        if len(batch) == 1:
            self._request("POST", "/api/v1/sessions/%s/events" % self.session_id, batch[0])
        else:
            self._request(
                "POST",
                "/api/v1/sessions/%s/events:batch" % self.session_id,
                {"events": batch},
            )

    def flush(self):
        """Send all queued events now. Raises AgentTrailError on persistent failure."""
        with self._lock:
            batch = list(self._queue)
            self._queue = []
        if batch:
            self._send_batch(batch)

    def close(self):
        """Flush remaining events and stop the background thread."""
        try:
            self.flush()
        finally:
            self._stop.set()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    # ---------------------------------------------------------------- sessions
    def start_session(self, agent_type, agent_version=None, model=None, cwd=None,
                      summary=None, metadata=None, client_session_id=None):
        """Create a session; returns the server-assigned session id."""
        _status, body = self._request("POST", "/api/v1/sessions", {
            "agentType": agent_type,
            "agentVersion": agent_version,
            "model": model,
            "cwd": cwd,
            "summary": summary,
            "metadata": metadata or {},
            "clientSessionId": client_session_id or str(uuid.uuid4()),
        })
        self.session_id = (body or {}).get("id")
        if not self.session_id:
            raise AgentTrailError("session create returned no id")
        self._enqueue({"type": "session_start", "actor": "system",
                       "summary": _trunc(summary or "session started", 280)})
        return self.session_id

    def end_session(self, status="completed", summary=None):
        """Post session_end, flush everything, and PATCH the session status."""
        self._require_session()
        self._enqueue({"type": "session_end", "actor": "system",
                       "summary": _trunc(summary or "session ended (%s)" % status, 280)})
        self.flush()
        self._request("PATCH", "/api/v1/sessions/%s" % self.session_id, {"status": status})
        self.close()

    # ------------------------------------------------------------------ events
    @contextlib.contextmanager
    def tool(self, name, summary=None, detail=None, tags=None, tokens_in=0,
             tokens_out=0, cost_usd=0.0):
        """Time a tool call and post a ``tool_call`` event on exit.

        On exception, posts the tool_call (marked failed) plus an ``error``
        event, then re-raises.
        """
        start = time.time()
        try:
            yield self
        except Exception as e:  # noqa: BLE001
            duration_ms = int((time.time() - start) * 1000)
            err_summary = "%s failed: %s: %s" % (name, type(e).__name__, e)
            self._enqueue({
                "type": "tool_call", "name": name,
                "summary": _trunc(summary or err_summary, 280),
                "detail": detail or {}, "durationMs": duration_ms,
                "tags": (tags or []) + ["failure"],
            })
            self.error(name, err_summary, {"exception": "%s: %s" % (type(e).__name__, e)})
            raise
        duration_ms = int((time.time() - start) * 1000)
        self._enqueue({
            "type": "tool_call", "name": name,
            "summary": _trunc(summary or "%s completed in %.1fs" % (name, duration_ms / 1000.0), 280),
            "detail": detail or {}, "durationMs": duration_ms,
            "tokensIn": tokens_in, "tokensOut": tokens_out, "costUsd": cost_usd,
            "tags": tags or [],
        })

    def message(self, role, text, name=None):
        """Post a ``message`` event. Role maps to actor: user/system/agent."""
        actor = {"user": "user", "system": "system", "agent": "agent",
                 "assistant": "agent"}.get(role, "agent")
        first_line = text.strip().splitlines()[0] if text.strip() else ""
        self._enqueue({
            "type": "message", "actor": actor, "name": name or "%s message" % role,
            "summary": _trunc(first_line, 280),
            "detail": {"text": _trunc(text, 4000)},
        })

    def error(self, name, summary, detail=None, tags=None):
        """Post an ``error`` event."""
        self._enqueue({
            "type": "error", "name": name,
            "summary": _trunc(summary, 280),
            "detail": detail or {},
            "tags": tags or [],
        })

    def handoff(self, to, summary, detail=None):
        """Post a ``handoff`` event to a subagent."""
        self._enqueue({
            "type": "handoff", "actor": "subagent:%s" % to, "name": "handoff",
            "summary": _trunc(summary, 280),
            "detail": detail or {"to": to},
        })

    def file_change(self, path, action="edit", summary=None, detail=None):
        """Post a ``file_change`` event (action: create/edit/delete)."""
        self._enqueue({
            "type": "file_change", "name": path,
            "summary": _trunc(summary or "%s %s" % (action, path), 280),
            "detail": dict(detail or {}, **{"path": path, "action": action}),
        })

    def cost(self, tokens_in=0, tokens_out=0, cost_usd=0.0, summary=None):
        """Post a ``cost`` event with token/cost accounting."""
        self._enqueue({
            "type": "cost",
            "summary": _trunc(
                summary or "cost: %d in / %d out tokens ($%.4f)"
                % (tokens_in, tokens_out, cost_usd), 280),
            "tokensIn": tokens_in, "tokensOut": tokens_out, "costUsd": cost_usd,
        })
