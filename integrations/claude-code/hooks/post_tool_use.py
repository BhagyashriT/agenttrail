#!/usr/bin/env python3
"""Claude Code PostToolUse hook: post ONE tool_call event (and an error event on failure)."""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import (  # noqa: E402
    ensure_session,
    failed,
    load_state,
    post_event,
    read_stdin_json,
    run,
    save_state,
    summarize_tool,
    trunc,
)


def _output_text(tool_response):
    if not isinstance(tool_response, dict):
        return trunc(tool_response, 2000)
    for key in ("stdout", "output", "result", "text"):
        if tool_response.get(key):
            return trunc(tool_response[key], 2000)
    return ""


def _stderr_text(tool_response):
    if not isinstance(tool_response, dict):
        return ""
    for key in ("stderr", "error"):
        if tool_response.get(key):
            return trunc(tool_response[key], 1000)
    return ""


def main():
    payload = read_stdin_json()
    claude_id = payload.get("session_id") or payload.get("sessionId") or "unknown"
    tool_name = payload.get("tool_name") or payload.get("toolName") or "unknown"
    tool_input = payload.get("tool_input") or payload.get("toolInput") or {}
    tool_response = payload.get("tool_response") or payload.get("toolResponse") or {}

    trail_id = ensure_session(claude_id, payload)

    # Match the stashed start (LIFO per session+tool); fall back to now if missing.
    state = load_state()
    stack = state["pending"].get(claude_id, {}).get(tool_name, [])
    start = stack.pop()["start"] if stack else time.time()
    save_state(state)
    duration_ms = max(0, int((time.time() - start) * 1000))

    is_failed, exit_code = failed(tool_name, tool_response)
    summary = summarize_tool(tool_name, tool_input, exit_code, duration_ms)
    detail = {
        "toolInput": trunc(json.dumps(tool_input), 2000),
        "outputHead": _output_text(tool_response),
        "stderrHead": _stderr_text(tool_response),
        "exitCode": exit_code,
    }
    post_event(
        trail_id,
        {
            "type": "tool_call",
            "actor": "agent",
            "name": tool_name,
            "summary": trunc(summary, 280),
            "detail": detail,
            "durationMs": duration_ms,
            "tags": ["failure"] if is_failed else [],
        },
    )

    if is_failed:
        excerpt = _stderr_text(tool_response) or _output_text(tool_response) or "no output captured"
        first_line = excerpt.strip().splitlines()[0] if excerpt.strip() else "no output captured"
        post_event(
            trail_id,
            {
                "type": "error",
                "actor": "agent",
                "name": tool_name,
                "summary": trunc("%s failed (exit %s): %s" % (tool_name, exit_code, first_line), 280),
                "detail": {"excerpt": trunc(excerpt, 2000), "exitCode": exit_code},
                "tags": ["tool-failure"],
            },
        )


run(main)
