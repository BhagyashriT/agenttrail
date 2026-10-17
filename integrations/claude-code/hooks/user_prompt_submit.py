#!/usr/bin/env python3
"""Claude Code UserPromptSubmit hook: record the user's prompt as a message event."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import ensure_session, post_event, read_stdin_json, run, trunc  # noqa: E402


def main():
    payload = read_stdin_json()
    claude_id = payload.get("session_id") or payload.get("sessionId") or "unknown"
    prompt = payload.get("prompt") or ""
    trail_id = ensure_session(claude_id, payload)
    first_line = prompt.strip().splitlines()[0] if prompt.strip() else "(empty prompt)"
    post_event(
        trail_id,
        {
            "type": "message",
            "actor": "user",
            "name": "user prompt",
            "summary": trunc(first_line, 280),
            "detail": {"prompt": trunc(prompt, 4000)},
        },
    )


run(main)
