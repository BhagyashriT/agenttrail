#!/usr/bin/env python3
"""Claude Code SubagentStop hook: record the subagent handoff as a handoff event."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import ensure_session, post_event, read_stdin_json, run, trunc  # noqa: E402


def main():
    payload = read_stdin_json()
    claude_id = payload.get("session_id") or payload.get("sessionId") or "unknown"
    name = (
        payload.get("agent_name")
        or payload.get("agentName")
        or payload.get("agent_id")
        or payload.get("agentId")
        or "subagent"
    )
    trail_id = ensure_session(claude_id, payload)
    post_event(
        trail_id,
        {
            "type": "handoff",
            "actor": "subagent:%s" % name,
            "name": "subagent stop",
            "summary": trunc("subagent '%s' finished" % name, 280),
            "detail": {"agentName": str(name)},
        },
    )


run(main)
