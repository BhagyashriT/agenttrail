# AgentTrail Server — Design Notes

## seq assignment (event ordering guarantee)

- **Single ingest** (`POST /api/v1/sessions/{id}/events`): `@Transactional` (default
  isolation), `seq = COALESCE(MAX(seq), 0) + 1` for the session.
- **Batch ingest** (`POST .../events:batch`): `@Transactional(isolation = SERIALIZABLE)`,
  `MAX(seq)` read once, then consecutive seqs assigned in request order. The
  `UNIQUE(session_id, seq)` constraint is the backstop; on
  `DataIntegrityViolationException` the whole batch is retried **once in a fresh
  transaction** (the first transaction rolls back, so retry state is clean).
- SERIALIZABLE was chosen over a per-session DB sequence because it keeps the DDL
  portable (no Postgres-specific `CREATE SEQUENCE` per session) and H2 supports it.
  Cost: batch throughput is serialized per session under contention — acceptable for
  an observability ingest path; if it ever becomes a bottleneck, a per-session
  sequence (Postgres) or an atomic counter table would be the next step.
- The single-ingest path deliberately does **not** use SERIALIZABLE: concurrent
  singles could still collide on `MAX(seq)+1`; the unique constraint rejects the
  loser with a 409-style `DataIntegrityViolationException` rather than silently
  duplicating — correctness over convenience. (Batch is the recommended path for
  concurrent writers.)

## Idempotency

- **Session create** with `clientSessionId`: a repeat returns the existing session
  with `200` instead of `201`. Enforced by the `UNIQUE(client_session_id)` column.
- **Event ingest** with client-supplied `id`: a repeat returns the existing event
  with `200` (single) or a `{"status":"duplicate","seq":<original>}` item (batch).
- Client `seq` values are **not** accepted: seq is always server-assigned, and a
  duplicate `(session_id, seq)` is rejected — this is the ordering guarantee in the
  contract.

## TEXT vs JSONB for `metadata` / `detail` / `tags`

Stored as `TEXT` via a Jackson `AttributeConverter` (`JsonColumnConverter`), **not**
JSONB, deliberately:

- One Flyway migration runs unchanged on PostgreSQL (prod) and H2 (dev/test) —
  zero DDL divergence, zero Hibernate dialect tricks.
- Tradeoff: no DB-side JSON operators (`->>`, `@>`). All filtering on JSON content
  is done in Java. If Postgres-only JSON querying is ever needed, the migration
  path is: change column type to `jsonb`, replace the converter with a
  PGobject-based one, and keep H2 dev on TEXT (or move dev to Testcontainers
  Postgres).

## Stats computation

- `stats` on session reads is **never stored**; it is computed per read from one
  aggregate query (`COUNT`, conditional `SUM`s, `SUM(cost_usd)`) — one query per
  session on single reads, one `GROUP BY session_id` query for session lists.
  No N+1.
- `avgSessionDurationMs` in the overview is averaged over **ended** sessions only
  (sessions without `endedAt` are excluded), computed in Java from a single
  session query to stay portable across H2/Postgres (no dialect-specific epoch
  arithmetic).
- `costByDay` groups by UTC calendar day in Java for the same portability reason.

## Auth

`ApiKeyFilter` (a `OncePerRequestFilter`) requires `X-API-Key` to equal
`${AGENTTRAIL_API_KEY:dev-key}` on every `/api/**` route except
`GET /api/v1/health`. Failures return `401 {"error":"unauthorized","message":"..."}`.
The key is compared in constant structure but not constant time — fine for a dev
default; use a real secret in prod.
