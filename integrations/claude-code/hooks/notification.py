#!/usr/bin/env python3
"""Claude Code Notification hook: record system notifications as message events."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import ensure_session, post_event, read_stdin_json, run, trunc  # noqa: E402


def main():
    payload = read_stdin_json()
    claude_id = payload.get("session_id") or payload.get("sessionId") or "unknown"
    message = payload.get("message") or ""
    trail_id = ensure_session(claude_id, payload)
    post_event(
        trail_id,
        {
            "type": "message",
            "actor": "system",
            "name": "notification",
            "summary": trunc(message.strip().splitlines()[0] if message.strip() else "notification", 280),
            "detail": {"message": trunc(message, 2000)},
        },
    )


run(main)
