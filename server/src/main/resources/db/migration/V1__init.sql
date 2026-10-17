-- AgentTrail V1: sessions + events
-- Portable DDL for PostgreSQL and H2. JSON-shaped columns (metadata, detail, tags)
-- are stored as TEXT via a JPA AttributeConverter (see server/NOTES.md for the tradeoff).

CREATE TABLE sessions (
    id                UUID PRIMARY KEY,
    client_session_id UUID UNIQUE,
    agent_type        VARCHAR(64),
    agent_version     VARCHAR(64),
    model             VARCHAR(128),
    cwd               VARCHAR(1024),
    status            VARCHAR(32) NOT NULL DEFAULT 'running',
    started_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at          TIMESTAMP WITH TIME ZONE,
    summary           VARCHAR(1024),
    metadata          TEXT
);

CREATE INDEX idx_sessions_started_at ON sessions (started_at);
CREATE INDEX idx_sessions_agent_type ON sessions (agent_type);
CREATE INDEX idx_sessions_status ON sessions (status);

CREATE TABLE events (
    id              UUID PRIMARY KEY,
    session_id      UUID NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    seq             INTEGER NOT NULL,
    ts              TIMESTAMP WITH TIME ZONE NOT NULL,
    event_type      VARCHAR(32) NOT NULL,
    actor           VARCHAR(128),
    name            VARCHAR(256),
    summary         VARCHAR(280),
    detail          TEXT,
    duration_ms     BIGINT,
    tokens_in       INTEGER,
    tokens_out      INTEGER,
    cost_usd        NUMERIC(12, 4),
    parent_event_id UUID,
    tags            TEXT,
    CONSTRAINT uq_events_session_seq UNIQUE (session_id, seq)
);

CREATE INDEX idx_events_session_seq ON events (session_id, seq);
CREATE INDEX idx_events_ts ON events (ts);
CREATE INDEX idx_events_type ON events (event_type);
CREATE INDEX idx_events_session_type ON events (session_id, event_type);
