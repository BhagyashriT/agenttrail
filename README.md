# AgentTrail — flight recorder for AI agents

When an agent fails at 2 AM, "it just didn't work" isn't a debugging story.
AgentTrail records everything an agent did — every tool call with its duration
and exit code, every error with the failure excerpt, every handoff, every file
change, every token spent — as an append-only, replayable event log you can
query and browse.

Built for the way agents actually get debugged: **replay the session, find the
step that went wrong.**

## 60-second quickstart

```sh
docker compose up                                    # API :8080, dashboard :5173
./integrations/claude-code/install.sh                # trace every Claude Code session
curl -X POST -H "X-API-Key: dev-key" localhost:8080/api/v1/demo/seed
```

Open the dashboard, pick the seeded session, and walk the timeline: tool calls,
the error, the fix, the cost. Then open a Claude Code session and watch your
own runs land in real time.

No Claude Code? Wrap any CLI:

```sh
./integrations/codex/run_wrapped.sh "my-task" -- my-agent --prompt "fix the bug"
```

## Dashboard tour

- **Sessions** — every recorded run, filterable by agent type, status, and
  free-text search over summaries. Per-session stats (event/error/tool counts,
  cost, duration) are computed server-side.
- **Timeline** — the session as an ordered event stream (`seq`-ordered):
  prompts, tool calls with 1-line human summaries (`` ran `npm test` (exit 1, 4.2s) ``),
  errors with excerpts, handoffs, file changes, cost events.
- **Errors** — newest-first view across sessions, so a repeated failure mode
  (same tool, same excerpt) is visible at a glance.
- **Overview** — totals, top tools, errors by tool, cost by day.

## API overview

Base URL `http://localhost:8080`. Auth: `X-API-Key` header (default `dev-key`).
Full contract: [`docs/API_CONTRACT.md`](docs/API_CONTRACT.md).

| Method & path | What it does |
|---|---|
| `GET /api/v1/health` | liveness, no auth |
| `POST /api/v1/sessions` | create a session (`agentType`, `clientSessionId` for idempotency) |
| `GET /api/v1/sessions?...` | list sessions (filters, search, pagination) with stats |
| `GET /api/v1/sessions/{id}` | one session with stats |
| `PATCH /api/v1/sessions/{id}` | set `status` / `endedAt` / `summary` |
| `POST /api/v1/sessions/{id}/events` | append one event (`type`, `ts` required; `seq` assigned) |
| `POST /api/v1/sessions/{id}/events:batch` | append up to 500 events atomically, per-item results |
| `GET /api/v1/sessions/{id}/events?...` | events ordered by `seq`, filterable by type/actor/search |
| `GET /api/v1/stats/overview` | totals, top tools, errors by type, cost by day |
| `GET /api/v1/stats/errors` | recent error events across sessions |
| `POST /api/v1/demo/seed` | create a realistic sample session |

Event types: `session_start` · `session_end` · `tool_call` · `message` · `error` ·
`handoff` · `file_change` · `cost` · `approval` · `checkpoint` · `custom`.

## Repo layout

```
agenttrail/
├── server/                  # Java 21 + Spring Boot 3 API, Flyway, Postgres (H2 in dev)
├── web/                     # React + Vite dashboard
├── integrations/
│   ├── claude-code/         # hooks: per-tool session tracing (install.sh + 7 hooks + tests)
│   └── codex/               # run_wrapped.sh: trace ANY agent CLI, no hooks API needed
├── sdks/
│   ├── python/              # pip-installable Tracer: batching, retries, context-manager tools
│   └── java/                # com.agenttrail:agenttrail-java — zero-dep, same surface
├── demo/
│   └── simulate_session.py  # realistic debugging session vs. the SDK (--stub for offline)
└── docs/
    ├── API_CONTRACT.md      # source of truth both sides implement against
    ├── ARCHITECTURE.md      # data flow, seq strategy, idempotency, 100x-scale notes
    └── CLAUDE_CODE_SETUP.md # install, verify, troubleshooting
```

## Design decisions worth knowing

- **Append-only per-session event log** — debugging is replay; history is never
  rewritten by a retry.
- **`seq` per session, server-assigned**, with a unique `(session_id, seq)`
  constraint and serializable batch ingest — the ordering guarantee.
- **Idempotent ingest everywhere** — client-supplied ids mean at-least-once
  delivery never double-records.
- **Integrations push, dashboard pulls** — a new agent needs only HTTP + the
  API key; nothing to deploy server-side.
- **Hooks can never break your session** — try/except around everything, 3s
  timeout, one log line on failure, always exit 0 (and the key is never logged).

## Roadmap

- **OpenTelemetry export** — bridge AgentTrail events to OTel traces so agent
  runs show up in existing observability stacks.
- **PR review agent adapter** — record review runs (files touched, comments
  posted, verdicts) the same way interactive sessions are recorded.
- **Hosted cloud** — multi-workspace API keys, OIDC login, retention policies;
  the single-key auth model is the thing that has to grow up first.

## License

MIT.

## Architecture

```mermaid
flowchart LR
    HOOKS[Claude Code hooks] --> API[POST /api/v1/sessions\nPOST /api/v1/sessions/{id}/events]
    SDK[Python / Java SDKs\nbatching + retries] --> API
    WRAP[run_wrapped.sh\nany CLI] --> API
    API --> INGEST[idempotent ingest\nclient UUIDs +\nunique(session_id, seq)]
    INGEST --> DB[(Postgres prod\nH2 dev)]
    DB --> DASH[React dashboard\nsessions · timeline · errors · overview]
    DB --> STATS[rollups\ncost · errors by tool]
```

## Design trade-offs

- **Client-supplied ids + `unique(session_id, seq)` backstop** — at-least-once delivery from hooks and SDKs never double-records, even if a retry races the original. The price: clients must generate UUIDs; server-assigned ids would be simpler but couldn't deduplicate retries.
- **Integrations push, dashboard pulls** — a new agent integration needs only HTTP + an API key; nothing to deploy server-side. WebSockets would give live tailing but add connection state the server doesn't need yet.
- **Hooks can never break your session** — try/except around everything, 3s timeout, always exit 0, and the key is never logged. Observability that crashes the thing it observes is worse than no observability.
- **Postgres in prod, H2 in dev** — zero-setup local runs; Flyway keeps both schemas honest.

## Known limitations

- Single API-key auth — fine for personal and small-team use; multi-workspace keys + OIDC are on the roadmap before any hosted offering.
- Cost rollups depend on reported token usage; models that don't report usage show as unknown, not zero.
- 15 MockMvc integration tests + 9 pytest hook tests green.
