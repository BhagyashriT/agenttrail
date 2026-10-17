#!/usr/bin/env python3
"""Claude Code PreToolUse hook: stash the tool start time for later duration math."""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import ensure_session, load_state, read_stdin_json, run, save_state  # noqa: E402


def main():
    payload = read_stdin_json()
    claude_id = payload.get("session_id") or payload.get("sessionId") or "unknown"
    tool_name = payload.get("tool_name") or payload.get("toolName") or "unknown"
    ensure_session(claude_id, payload)  # make sure the session exists even if start was missed
    state = load_state()
    stack = state["pending"].setdefault(claude_id, {}).setdefault(tool_name, [])
    stack.append({"start": time.time()})
    save_state(state)


run(main)
