"""Shared helpers for AgentTrail Claude Code hooks.

Golden rule: a hook must NEVER break the user's Claude session.
Every entry point wraps its logic via ``run()``: 3s HTTP timeout,
on any failure append ONE line to ``~/.agenttrail/hooks.log`` and exit 0.
The API key is never printed or logged.
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

STATE_DIR = os.path.join(os.path.expanduser("~"), ".agenttrail")
STATE_PATH = os.path.join(STATE_DIR, "state.json")
LOG_PATH = os.path.join(STATE_DIR, "hooks.log")
HTTP_TIMEOUT = 3.0


def utcnow_iso():
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def base_url():
    return os.environ.get("AGENTTRAIL_URL", "http://localhost:8080").rstrip("/")


def api_key():
    return os.environ.get("AGENTTRAIL_API_KEY", "dev-key")


def log_line(msg):
    """Append a single line to the hooks log. Never includes the API key."""
    try:
        os.makedirs(STATE_DIR, exist_ok=True)
        with open(LOG_PATH, "a", encoding="utf-8") as f:
            f.write("%s %s\n" % (utcnow_iso(), msg))
    except Exception:
        pass


def load_state():
    try:
        with open(STATE_PATH, encoding="utf-8") as f:
            data = json.load(f)
            if isinstance(data, dict):
                data.setdefault("sessions", {})
                data.setdefault("pending", {})
                return data
    except Exception:
        pass
    return {"sessions": {}, "pending": {}}


def save_state(state):
    os.makedirs(STATE_DIR, exist_ok=True)
    tmp = STATE_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(state, f)
    os.replace(tmp, STATE_PATH)


def request(method, path, payload=None):
    """Single HTTP call. Raises on any failure; caller decides how to handle."""
    url = base_url() + path
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={"Content-Type": "application/json", "X-API-Key": api_key()},
    )
    with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
        body = resp.read().decode("utf-8")
        return resp.status, (json.loads(body) if body.strip() else None)


def post_event(session_id, event):
    """POST one event to a session. Returns the created event body (or None)."""
    event = dict(event)
    event.setdefault("ts", utcnow_iso())
    event.setdefault("actor", "agent")
    _status, body = request("POST", "/api/v1/sessions/%s/events" % session_id, event)
    return body


def ensure_session(claude_session_id, payload=None):
    """Return the AgentTrail session id for a Claude session, creating it if needed.

    Idempotent: the mapping is cached in the state file, and session creation
    passes a deterministic ``clientSessionId`` so a retried create returns the
    existing session instead of duplicating it.
    """
    payload = payload or {}
    state = load_state()
    trail_id = state["sessions"].get(claude_session_id)
    if trail_id:
        return trail_id
    client_session_id = str(
        uuid.uuid5(uuid.NAMESPACE_DNS, "agenttrail-claude-" + str(claude_session_id))
    )
    _status, body = request(
        "POST",
        "/api/v1/sessions",
        {
            "agentType": "claude-code",
            "agentVersion": payload.get("version"),
            "model": payload.get("model"),
            "cwd": payload.get("cwd"),
            "clientSessionId": client_session_id,
        },
    )
    trail_id = (body or {}).get("id")
    if not trail_id:
        raise RuntimeError("session create returned no id")
    state["sessions"][claude_session_id] = trail_id
    save_state(state)
    post_event(
        trail_id,
        {"type": "session_start", "actor": "system", "summary": "Claude Code session started"},
    )
    return trail_id


def trunc(text, limit):
    text = "" if text is None else str(text)
    if len(text) <= limit:
        return text
    return text[:limit] + "...(truncated)"


def summarize_tool(tool_name, tool_input, exit_code, duration_ms):
    """One-line human summary for a tool call, e.g. 'ran `npm test` (exit 1, 4.2s)'."""
    tool_input = tool_input or {}
    secs = "%.1fs" % (duration_ms / 1000.0)

    def get(*keys):
        for k in keys:
            if tool_input.get(k):
                return tool_input[k]
        return ""

    if tool_name == "Bash":
        cmd = get("command")
        return "ran `%s` (exit %s, %s)" % (trunc(cmd, 120), exit_code, secs)
    if tool_name == "Read":
        return "read %s" % trunc(get("file_path", "path"), 140)
    if tool_name == "Write":
        return "wrote %s" % trunc(get("file_path", "path"), 140)
    if tool_name == "Edit":
        return "edited %s" % trunc(get("file_path", "path"), 140)
    if tool_name == "Glob":
        return "globbed `%s`" % trunc(get("pattern"), 120)
    if tool_name == "Grep":
        return "searched for `%s`" % trunc(get("pattern"), 120)
    if tool_name == "TodoWrite":
        todos = tool_input.get("todos") or []
        return "updated todo list (%d items)" % len(todos)
    if tool_name == "Task":
        return "launched subagent: %s" % trunc(get("description", "subagent_task"), 140)
    if tool_name == "WebFetch":
        return "fetched %s" % trunc(get("url"), 140)
    if tool_name == "WebSearch":
        return "web search: `%s`" % trunc(get("query"), 120)
    return "%s %s (%s)" % (tool_name, trunc(json.dumps(tool_input)[:100], 100), secs)


def failed(tool_name, tool_response):
    """Best-effort failure detection across tool response shapes."""
    if not isinstance(tool_response, dict):
        return False, None
    for key in ("exitCode", "exit_code", "code"):
        if key in tool_response and tool_response[key] not in (None, 0, "0"):
            return True, tool_response[key]
    if tool_response.get("is_error") or tool_response.get("error"):
        return True, tool_response.get("exitCode", "error")
    return False, 0


def run(fn):
    """Entry-point wrapper: never break the user's session, never leak the key."""
    try:
        fn()
    except Exception as e:  # noqa: BLE001 - hooks must not raise, ever
        log_line("%s failed: %s: %s" % (os.path.basename(sys.argv[0]), type(e).__name__, e))
    sys.exit(0)


def read_stdin_json():
    try:
        raw = sys.stdin.read()
        return json.loads(raw) if raw.strip() else {}
    except Exception:
        return {}
