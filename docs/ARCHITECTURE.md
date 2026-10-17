# AgentTrail Architecture

AgentTrail is a **flight recorder for AI agents**: every agent session becomes an
append-only log of timestamped events (tool calls, messages, errors, handoffs,
file changes, cost), queryable through a REST API and browsable in a dashboard.
The contract in `docs/API_CONTRACT.md` is the source of truth for all components.

## Components

```
┌──────────────┐   hooks / SDK    ┌────────────────────────┐
│ Claude Code  │ ───────────────▶ │  Server (Java/Spring)  │
│ hooks        │  POST /api/v1/*  │  sessions + events     │
└──────────────┘  X-API-Key       │  Postgres (H2 in dev)  │
┌──────────────┐                  └───────────┬────────────┘
│ run_wrapped  │ ───────────────▶             │ GET /api/v1/*
│ (any CLI)    │                              ▼
└──────────────┘                  ┌────────────────────────┐
┌──────────────┐                  │ Web dashboard          │
│ Python /     │ ───────────────▶ │ (React + Vite)         │
│ Java SDKs    │                  │ timeline, errors, cost │
└──────────────┘                  └────────────────────────┘
```

| Component | Role | Location |
|---|---|---|
| Server | REST API, persistence, stats rollups | `server/` (Java 21, Spring Boot 3, Flyway) |
| Web dashboard | Session timeline, error/cost views | `web/` (React + Vite) |
| Claude Code hooks | Per-tool fidelity: session/message/tool/error/handoff events | `integrations/claude-code/` |
| Generic wrapper | Coarse tracing for any CLI (one session per invocation) | `integrations/codex/run_wrapped.sh` |
| Python SDK | Instrument any Python agent; background batching | `sdks/python/` |
| Java SDK | Same surface for JVM agents; zero runtime deps | `sdks/java/` |
| Demo seeder | `POST /api/v1/demo/seed` + `demo/simulate_session.py` | `server/`, `demo/` |

The server never calls out: all integrations are **push** (they POST to the API).
The dashboard is **pull** (it GETs from the API). This keeps every integration
independent — a new agent needs only an HTTP client and the API key.

## Data flow (one tool call, Claude Code path)

1. `PreToolUse` hook fires → stashes `{start: now}` in `~/.agenttrail/state.json`,
   keyed by Claude session + tool name (a stack, so parallel calls don't collide).
2. Tool runs. `PostToolUse` hook fires → pops the start, computes `durationMs`,
   builds a 1-line human summary (`ran \`npm test\` (exit 1, 4.2s)`), truncates
   output to ~2 KB, POSTs one `tool_call` event. On failure it also POSTs an
   `error` event with the failure excerpt.
3. Server assigns `seq` (per-session monotonic int), persists, returns `201`.
4. Dashboard polls `GET /api/v1/sessions/{id}/events` (ordered by `seq` ASC) and
   renders the timeline.

## Why an append-only per-session event log

- **Debugging is replay.** An agent failure is understood by walking the event
  sequence, not by staring at a final state. Append-only means history is never
  rewritten by a later retry or a buggy client.
- **Sessions are the unit of sense-making.** Cost, errors, and duration only
  mean something grouped by the task that produced them. Everything hangs off
  a session id.
- **Simple consistency story.** Within a session, `seq` gives a total order;
  across sessions there is no ordering promise, which is all a dashboard needs.

## `seq` ordering strategy

`seq` is a per-session monotonically increasing integer. The server assigns
`MAX(seq)+1` (or honors a client-supplied `seq`) and enforces a unique
constraint on `(session_id, seq)` — a duplicate is rejected with `409`.

Concurrent batch inserts are the hazard: two batches racing `MAX(seq)` would
compute the same value. The contract's chosen approach is a
`@Transactional(isolation = SERIALIZABLE)` batch ingest plus the unique
constraint as backstop, retrying once on violation. Serializable transactions
serialize the `MAX(seq)` read-modify-write; the constraint catches anything
that slips through. For current write volumes (one agent, bursty tool calls)
this is correct and simple; at higher concurrency a per-session Postgres
sequence would be the next step.

## Idempotent ingest

Every write path accepts a client-generated idempotency key:

- `POST /api/v1/sessions` accepts `clientSessionId` — same value returns the
  existing session (`200`) instead of creating a duplicate.
- `POST .../events` accepts an event `id` — same id returns the existing event
  (`200`) instead of duplicating.
- `POST .../events:batch` applies the same rule per item; the whole batch is
  one transaction and per-item results (`created`/`duplicate`) come back in order.

This is what makes at-least-once delivery safe: hooks and SDKs can retry on
network failure without fear of double-recording. SDKs generate a UUID v4 per
event for this purpose; the Claude hooks pass a deterministic
`clientSessionId` derived from the Claude session id.

## JSONB-vs-TEXT tradeoff

`events.detail` and `sessions.metadata` are schemaless JSON — the whole point
is that new integrations can attach new fields without migrations.

- **Postgres (prod): JSONB.** Queryable (`detail->>'exitCode'`), indexable with
  GIN when needed, compact binary storage. The cost: Postgres-specific column
  types in the Flyway migration.
- **H2 (dev/test): TEXT.** The JPA layer serializes the map to a JSON string
  and stores it as TEXT. The cost: no server-side JSON querying in dev — but
  dev doesn't need it, and the application code sees the same `Map` either way.

The tradeoff is portability of the dev loop vs. query power in prod. If a
future feature needs to filter on `detail` fields in dev too, H2 2.x does
support a JSON type — that migration is the escape hatch.

## Auth model

Single shared API key via the `X-API-Key` header (`AGENTTRAIL_API_KEY`,
default `dev-key`). Missing/invalid → `401`. Only `/api/v1/health` is
unauthenticated.

This is deliberately minimal for a local-first v1: the threat model is "my
laptop / my tailnet", not multi-tenant SaaS. Integrations read the key from
the environment and **never log or print it** (the hooks test suite asserts
this). The honest limitation: one key means no per-agent scoping and rotation
is all-or-nothing — fine for self-hosted, insufficient for the hosted-cloud
roadmap item, which will need per-workspace keys or OIDC.

## What you'd do at 100x scale

Current design handles one developer's agents comfortably. At 100x (teams of
agents, thousands of sessions/day), the honest changes, in order:

1. **Partition `events` by session (Postgres declarative partitioning, e.g. by
   hash or by month).** Event reads are always per-session; partitioning keeps
   those reads and the `MAX(seq)` computation local.
2. **Move ingest off the request path.** SDK batching already coalesces writes,
   but the API should accept batches into **Kafka** and let consumers do the
   `seq` assignment + insert. Exactly-once-ish semantics come from the existing
   idempotency keys, not from the transport.
3. **ClickHouse (or similar) for analytics.** The `stats/overview` rollups
   (`SUM(cost)`, top tools, errors by day) are OLAP queries; they don't belong
   on the transactional Postgres. Dual-write events to ClickHouse, keep
   Postgres as the session/timeline source of truth.
4. **Per-session Postgres sequences** for `seq` assignment instead of
   serializable `MAX(seq)` — removes the transaction-isolation bottleneck under
   concurrent writers to the same session.
5. **Auth:** per-workspace API keys with scopes, short-lived tokens for the
   dashboard, audit log of key usage.

None of this is built — v1 optimizes for "runs on your laptop in one
`docker compose up`" over scale it doesn't have yet.
