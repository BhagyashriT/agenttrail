#!/usr/bin/env python3
"""Claude Code Stop hook: record session_end and mark the AgentTrail session completed."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import (  # noqa: E402
    ensure_session,
    load_state,
    post_event,
    read_stdin_json,
    request,
    run,
    save_state,
    trunc,
)


def main():
    payload = read_stdin_json()
    claude_id = payload.get("session_id") or payload.get("sessionId") or "unknown"
    trail_id = ensure_session(claude_id, payload)
    post_event(
        trail_id,
        {
            "type": "session_end",
            "actor": "system",
            "name": "session end",
            "summary": trunc("Claude Code session ended", 280),
        },
    )
    request("PATCH", "/api/v1/sessions/%s" % trail_id, {"status": "completed"})
    # Drop the mapping so a reused Claude session id starts fresh next time.
    state = load_state()
    state["sessions"].pop(claude_id, None)
    state["pending"].pop(claude_id, None)
    save_state(state)


run(main)
