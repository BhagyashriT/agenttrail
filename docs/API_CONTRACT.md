# AgentTrail API Contract (v1) — SOURCE OF TRUTH

All components (server, web dashboard, hooks, SDKs, demo seeder) MUST conform to this contract.
Base URL: `http://localhost:8080`. All JSON uses `application/json`.

## Auth

Header: `X-API-Key: <key>`. Default dev key: `dev-key` (override with env `AGENTTRAIL_API_KEY`).
Missing/invalid key → `401 {"error":"unauthorized"}`. Health endpoint is unauthenticated.

## Conventions

- IDs: UUID v4 strings, generated server-side unless the client supplies one (idempotent ingest).
- Timestamps: ISO-8601 UTC, e.g. `2026-10-16T18:00:00Z`.
- Pagination: query params `page` (0-based, default 0), `size` (default 20, max 200).
  Page envelope: `{"content":[...],"page":0,"size":20,"totalElements":123,"totalPages":7}`.
- Errors: `{"error":"<machine-code>","message":"<human text>"}` with proper HTTP status.

## Data model

### Session
```json
{
  "id": "3f9d...",
  "agentType": "claude-code",
  "agentVersion": "2.0.0",
  "model": "claude-opus-4-5",
  "cwd": "/home/user/myproj",
  "status": "running",
  "startedAt": "2026-10-16T18:00:00Z",
  "endedAt": null,
  "summary": "Fixed NPE in checkout flow",
  "metadata": {"host": "mbp", "branch": "fix/npe"},
  "stats": {"eventCount": 42, "errorCount": 3, "toolCallCount": 25, "costUsd": 1.8421, "durationMs": 3720000}
}
```
- `agentType`: free string; well-known values: `claude-code`, `codex-cli`, `custom`.
- `status`: `running` | `completed` | `failed` | `interrupted`.
- `stats` is computed server-side on reads (never accepted on writes).

### Event
```json
{
  "id": "9a1b...",
  "sessionId": "3f9d...",
  "seq": 7,
  "ts": "2026-10-16T18:01:12Z",
  "type": "tool_call",
  "actor": "agent",
  "name": "Bash",
  "summary": "ran `npm test` (exit 1, 4.2s)",
  "detail": {"command": "npm test", "exitCode": 1, "outputHead": "...truncated..."},
  "durationMs": 4200,
  "tokensIn": 0,
  "tokensOut": 0,
  "costUsd": 0,
  "parentEventId": null,
  "tags": ["test", "failure"]
}
```
- `type` enum: `session_start | session_end | tool_call | message | error | handoff | file_change | cost | approval | checkpoint | custom`.
- `actor`: `agent | user | system | subagent:<name>`.
- `seq`: per-session monotonically increasing integer, assigned server-side (max+1) unless the client
  supplies one; server rejects a duplicate `(sessionId, seq)` with 409 — this is the ordering guarantee.
- `detail`: arbitrary JSON object (stored as JSONB). `summary`: ≤ 280 chars, human-readable.
- `costUsd`: decimal; `tokensIn/tokensOut`: integers; all default 0.

## Endpoints

### Health
- `GET /api/v1/health` → `200 {"status":"UP","version":"0.1.0"}` (no auth).

### Sessions
- `POST /api/v1/sessions`
  Body: `{"agentType","agentVersion","model","cwd","summary","metadata","clientSessionId"}`
  (`clientSessionId`: optional client-generated UUID for idempotent creation — same value returns the existing session instead of creating a duplicate.)
  → `201` Session (with empty stats).
- `GET /api/v1/sessions?agentType=&status=&q=&from=&to=&page=&size=`
  `q` searches `summary` and `cwd` (case-insensitive substring). `from`/`to` filter `startedAt`.
  → `200` page of Session summaries (each with `stats`).
- `GET /api/v1/sessions/{id}` → `200` Session with `stats`, or `404`.
- `PATCH /api/v1/sessions/{id}`
  Body: any of `{"status","endedAt","summary"}`. Setting status to a terminal value
  (`completed|failed|interrupted`) without `endedAt` stamps `endedAt=now`.
  → `200` Session, or `404`.

### Events
- `POST /api/v1/sessions/{id}/events`
  Body: single event WITHOUT `id`/`sessionId`/`seq` (server assigns; or WITH `id` for idempotent retry —
  same `id` returns the existing event with `200` instead of duplicating).
  Required: `type`, `ts`. → `201` Event (or `200` on idempotent replay). `404` if session unknown.
- `POST /api/v1/sessions/{id}/events:batch`
  Body: `{"events":[{...}, {...}]}` (max 500 per batch). Each item follows the single-event rules;
  items may carry client `id`s for idempotency. The whole batch is one transaction; per-item results
  returned in order: `{"results":[{"id":"...","status":"created|duplicate","seq":7}, ...]}` → `200`.
  `404` if session unknown.
- `GET /api/v1/sessions/{id}/events?type=&actor=&q=&from=&to=&page=&size=`
  `type` may repeat (`?type=tool_call&type=error`). `q` searches `summary`+`name`.
  → `200` page of Events ordered by `seq` ASC. `404` if session unknown.

### Stats
- `GET /api/v1/stats/overview?from=&to=&agentType=`
  → `200`:
  ```json
  {
    "sessions": 12, "events": 1830, "toolCalls": 940, "errors": 37,
    "totalCostUsd": 18.42, "totalTokensIn": 1200000, "totalTokensOut": 340000,
    "avgSessionDurationMs": 2100000,
    "topTools": [{"name": "Bash", "count": 410}, {"name": "Edit", "count": 220}],
    "errorsByType": [{"name": "Bash", "count": 21}, {"name": "Edit", "count": 9}],
    "costByDay": [{"day": "2026-10-14", "costUsd": 4.2}]
  }
  ```
- `GET /api/v1/stats/errors?from=&to=&agentType=&page=&size=`
  → `200` page of recent error Events (type=error) with their `sessionId`, newest first.

### Demo
- `POST /api/v1/demo/seed` → `200 {"sessionId":"..."}` — creates one realistic sample session
  (a debugging session with tool calls, an error, a fix, cost events) so the dashboard has content.
  Safe to call repeatedly (creates a new session each time).

## Notes for implementers

- Server: Java 21, Spring Boot 3.x, Spring Data JPA, Flyway, Postgres (prod) / H2 (dev,test).
  Flyway migration `V1__init.sql` creates `sessions` and `events` tables; `detail`/`metadata` are JSONB
  on Postgres. For H2 dev mode, store them as TEXT (JPA `columnDefinition` handled via a converter —
  keep it simple: serialize JSON to String, column type TEXT).
- `seq` assignment must be safe under concurrent batch inserts: compute `COALESCE(MAX(seq),0)+ROW_NUMBER()`
  per batch inside a serializable transaction, or use a Postgres sequence per session... simplest correct
  approach: `@Transactional(isolation = SERIALIZABLE)` on batch ingest + unique constraint on
  `(session_id, seq)`; on constraint violation, retry once. Document the choice.
- Cost rollups: `SUM(cost_usd)` over events; tokens likewise. `stats` on session reads computed with
  one aggregate query (no N+1).
- Web dashboard: React + Vite, plain CSS, `fetch` against this API, API base URL from `VITE_API_URL`
  (default `http://localhost:8080`), API key from `VITE_API_KEY` (default `dev-key`).
- Hooks/SDKs: POST to these endpoints with `X-API-Key`; never log the key.
