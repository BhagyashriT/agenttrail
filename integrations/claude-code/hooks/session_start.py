#!/usr/bin/env python3
"""Claude Code SessionStart hook: create an AgentTrail session and cache the mapping."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import ensure_session, read_stdin_json, run  # noqa: E402


def main():
    payload = read_stdin_json()
    claude_id = payload.get("session_id") or payload.get("sessionId") or "unknown"
    ensure_session(claude_id, payload)


run(main)
