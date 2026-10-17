import { useState } from "react";
import { api } from "../api";
import type { Page, Session, TrailEvent } from "../types";
import { EVENT_TYPES } from "../types";
import {
  formatCost,
  formatDateTime,
  formatDuration,
  formatInt,
  truncate,
} from "../format";
import {
  Empty,
  ErrorBanner,
  Loading,
  Pagination,
  StatCard,
  StatusBadge,
  TypeBadge,
} from "../components/ui";
import { useApi } from "../components/hooks";
import { navigate } from "../components/router";

const PAGE_SIZE = 50;

function EventRow({
  event,
  expanded,
  onToggle,
}: {
  event: TrailEvent;
  expanded: boolean;
  onToggle: () => void;
}) {
  const isError = event.type === "error";
  return (
    <>
      <tr
        className={`row-clickable${isError ? " row-error" : ""}`}
        onClick={onToggle}
        aria-expanded={expanded}
      >
        <td className="mono num">{event.seq}</td>
        <td className="mono nowrap">{formatDateTime(event.ts)}</td>
        <td>
          <TypeBadge type={event.type} />
        </td>
        <td className="mono">{truncate(event.actor, 24)}</td>
        <td>
          <div className="event-name">{truncate(event.name || event.summary, 80)}</div>
          {event.type === "tool_call" && event.durationMs > 0 && (
            <div className="event-meta">⏱ {formatDuration(event.durationMs)}</div>
          )}
          {event.costUsd > 0 && (
            <div className="event-meta">💲 {formatCost(event.costUsd)}</div>
          )}
        </td>
        <td className="event-chevron">{expanded ? "▾" : "▸"}</td>
      </tr>
      {expanded && (
        <tr className={isError ? "row-error" : ""}>
          <td colSpan={6} className="detail-cell">
            <div className="detail-grid">
              <div>
                <span className="detail-label">Summary</span>
                <p>{event.summary || "—"}</p>
              </div>
              <div className="detail-stats">
                <span>
                  <span className="detail-label">Tokens in/out</span> {formatInt(event.tokensIn)} /{" "}
                  {formatInt(event.tokensOut)}
                </span>
                <span>
                  <span className="detail-label">Cost</span> {formatCost(event.costUsd)}
                </span>
                <span>
                  <span className="detail-label">Duration</span>{" "}
                  {event.durationMs > 0 ? formatDuration(event.durationMs) : "—"}
                </span>
                {event.parentEventId && (
                  <span>
                    <span className="detail-label">Parent</span>{" "}
                    <span className="mono">{truncate(event.parentEventId, 12)}…</span>
                  </span>
                )}
                {event.tags.length > 0 && (
                  <span>
                    <span className="detail-label">Tags</span> {event.tags.join(", ")}
                  </span>
                )}
              </div>
              <div>
                <span className="detail-label">Detail JSON</span>
                <pre className="detail-json">
                  {JSON.stringify(event.detail ?? null, null, 2)}
                </pre>
              </div>
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

export default function SessionDetail({ id }: { id: string }) {
  const [types, setTypes] = useState<Set<string>>(new Set());
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const session = useApi<Session>(() => api.getSession(id), `session-${id}`);

  const eventsKey = JSON.stringify({
    id,
    types: [...types].sort(),
    q,
    page,
  });
  const events = useApi<Page<TrailEvent>>(
    () =>
      api.getEvents(id, {
        type: types.size > 0 ? [...types] : undefined,
        q: q || undefined,
        page,
        size: PAGE_SIZE,
      }),
    eventsKey,
  );

  function toggleType(t: string) {
    setTypes((prev) => {
      const next = new Set(prev);
      if (next.has(t)) next.delete(t);
      else next.add(t);
      return next;
    });
    setPage(0);
    events.refresh();
  }

  function selectAll() {
    setTypes(new Set(EVENT_TYPES));
    setPage(0);
    events.refresh();
  }

  function selectNone() {
    setTypes(new Set());
    setPage(0);
    events.refresh();
  }

  const s = session.data;

  return (
    <div className="page">
      <button className="btn btn-ghost back-link" onClick={() => navigate("#/sessions")}>
        ← All sessions
      </button>

      {session.error && <ErrorBanner message={session.error} onRetry={session.refresh} />}
      {session.loading && !s && <Loading label="Loading session…" />}

      {s && (
        <>
          <div className="page-head">
            <h1 className="session-title">{truncate(s.summary, 90) || "Untitled session"}</h1>
            <StatusBadge status={s.status} />
          </div>
          <div className="session-meta mono">
            <span>{truncate(s.agentType, 24)}</span>
            <span>·</span>
            <span>{truncate(s.model, 30)}</span>
            <span>·</span>
            <span>{truncate(s.cwd, 40)}</span>
            <span>·</span>
            <span>{formatDateTime(s.startedAt)}</span>
            {s.endedAt && (
              <>
                <span>→</span>
                <span>{formatDateTime(s.endedAt)}</span>
              </>
            )}
          </div>

          <section className="stat-grid">
            <StatCard label="Duration" value={formatDuration(s.stats.durationMs)} />
            <StatCard label="Events" value={formatInt(s.stats.eventCount)} />
            <StatCard label="Errors" value={formatInt(s.stats.errorCount)} />
            <StatCard label="Tool calls" value={formatInt(s.stats.toolCallCount)} />
            <StatCard label="Cost" value={formatCost(s.stats.costUsd)} />
          </section>

          <div className="card">
            <h2>Event timeline</h2>
            <div className="filter-row timeline-filters">
              <div className="type-filter">
                <div className="type-filter-head">
                  <span>Type</span>
                  <button className="link-btn" onClick={selectAll}>
                    all
                  </button>
                  <span>/</span>
                  <button className="link-btn" onClick={selectNone}>
                    none
                  </button>
                </div>
                <div className="type-chips">
                  {EVENT_TYPES.map((t) => (
                    <label key={t} className={`chip${types.has(t) ? " chip-on" : ""}`}>
                      <input
                        type="checkbox"
                        checked={types.has(t)}
                        onChange={() => toggleType(t)}
                      />
                      {t}
                    </label>
                  ))}
                </div>
              </div>
              <label className="filter-field grow">
                <span>Search summary / name</span>
                <input
                  type="text"
                  placeholder="e.g. npm test"
                  value={q}
                  onChange={(e) => setQ(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      setPage(0);
                      events.refresh();
                    }
                  }}
                />
              </label>
              <div className="filter-actions">
                <button
                  className="btn"
                  onClick={() => {
                    setPage(0);
                    events.refresh();
                  }}
                >
                  Apply
                </button>
              </div>
            </div>

            {events.error && <ErrorBanner message={events.error} onRetry={events.refresh} />}
            {events.loading && !events.data && <Loading label="Loading events…" />}
            {events.data && events.data.content.length === 0 && (
              <Empty message="No events match these filters." />
            )}
            {events.data && events.data.content.length > 0 && (
              <>
                <table className="table timeline">
                  <thead>
                    <tr>
                      <th className="num">#</th>
                      <th>Time</th>
                      <th>Type</th>
                      <th>Actor</th>
                      <th>Event</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    {events.data.content.map((e) => (
                      <EventRow
                        key={e.id}
                        event={e}
                        expanded={expandedId === e.id}
                        onToggle={() => setExpandedId((cur) => (cur === e.id ? null : e.id))}
                      />
                    ))}
                  </tbody>
                </table>
                <Pagination
                  page={events.data.page}
                  totalPages={events.data.totalPages}
                  totalElements={events.data.totalElements}
                  onPage={(p) => {
                    setPage(p);
                    events.refresh();
                  }}
                />
              </>
            )}
          </div>
        </>
      )}
    </div>
  );
}
