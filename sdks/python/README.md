# agenttrail — Python SDK

Flight-recorder client for [AgentTrail](../../docs/API_CONTRACT.md), with zero
runtime dependencies (stdlib only).

## Install

```sh
pip install -e sdks/python   # from the repo root
```

## Quick start

```python
from agenttrail import Tracer

tracer = Tracer()  # reads AGENTTRAIL_URL / AGENTTRAIL_API_KEY, with sane defaults
tracer.start_session(agent_type="custom", summary="debug checkout flake")

tracer.message("user", "The checkout integration test is flaky")

with tracer.tool("Bash", summary="ran `pytest -x` (exit 1, 12.3s)",
                 detail={"command": "pytest -x", "exitCode": 1}):
    ...  # duration is timed automatically; exceptions post an error event

tracer.error("Bash", "pytest failed: 3 tests red", {"excerpt": "..."})
tracer.file_change("src/checkout.py", action="edit", summary="guarded None total")
tracer.handoff("test-runner", "handed full suite to test-runner subagent")
tracer.cost(tokens_in=42000, tokens_out=18000, cost_usd=0.21)

tracer.end_session("completed")  # posts session_end, flushes, PATCHes status
```

## Batching & reliability

- Events are queued and flushed by a background thread every `flush_interval`
  seconds (default 5) or when `batch_size` events (default 50) accumulate.
  Multi-event flushes use `POST .../events:batch`; every event carries a
  client-generated `id` so retried batches are idempotent server-side.
- Transient failures are retried with exponential backoff (`max_retries`,
  default 3). `AgentTrailError` is raised only after retries are exhausted.
- `flush()` sends everything queued right now; `close()` (or the context
  manager) flushes and stops the background thread. Always `flush()`/`close()`
  — or call `end_session()` — before your process exits.

## API surface

| Method | Event type |
|---|---|
| `start_session(agent_type, ...)` | `POST /api/v1/sessions` (+ `session_start` event) |
| `tool(name, **fields)` context manager | `tool_call` (auto-timed; `error` too on exception) |
| `message(role, text)` | `message` (role → actor) |
| `error(name, summary, detail)` | `error` |
| `handoff(to, summary, detail)` | `handoff` (actor `subagent:<to>`) |
| `file_change(path, action, summary, detail)` | `file_change` |
| `cost(tokens_in, tokens_out, cost_usd)` | `cost` |
| `end_session(status)` | `session_end` + `PATCH` session |
| `flush()` / `close()` | batch delivery control |
