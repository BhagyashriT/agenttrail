import { useState } from "react";
import { api } from "../api";
import type { Page, Session } from "../types";
import { formatCost, formatDateTime, formatDuration, formatInt, truncate } from "../format";
import { Empty, ErrorBanner, Loading, Pagination, StatusBadge } from "../components/ui";
import { useApi } from "../components/hooks";
import { navigate } from "../components/router";

const PAGE_SIZE = 20;

export default function Sessions() {
  const [agentType, setAgentType] = useState("");
  const [status, setStatus] = useState("");
  const [q, setQ] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [page, setPage] = useState(0);

  const key = JSON.stringify({ agentType, status, q, from, to, page });
  const sessions = useApi<Page<Session>>(
    () =>
      api.getSessions({
        agentType: agentType || undefined,
        status: status || undefined,
        q: q || undefined,
        from: from || undefined,
        to: to || undefined,
        page,
        size: PAGE_SIZE,
      }),
    key,
  );

  function applyFilters() {
    setPage(0);
    sessions.refresh();
  }

  function clearFilters() {
    setAgentType("");
    setStatus("");
    setQ("");
    setFrom("");
    setTo("");
    setPage(0);
    sessions.refresh();
  }

  const data = sessions.data;

  return (
    <div className="page">
      <div className="page-head">
        <h1>Sessions</h1>
      </div>

      <div className="card filter-bar">
        <div className="filter-row">
          <label className="filter-field">
            <span>Agent type</span>
            <input
              type="text"
              placeholder="e.g. claude-code"
              value={agentType}
              onChange={(e) => setAgentType(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && applyFilters()}
            />
          </label>
          <label className="filter-field">
            <span>Status</span>
            <select value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="">All</option>
              <option value="running">running</option>
              <option value="completed">completed</option>
              <option value="failed">failed</option>
              <option value="interrupted">interrupted</option>
            </select>
          </label>
          <label className="filter-field">
            <span>Search</span>
            <input
              type="text"
              placeholder="summary / cwd"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && applyFilters()}
            />
          </label>
          <label className="filter-field">
            <span>From</span>
            <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </label>
          <label className="filter-field">
            <span>To</span>
            <input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          </label>
          <div className="filter-actions">
            <button className="btn" onClick={applyFilters}>
              Apply
            </button>
            <button className="btn btn-ghost" onClick={clearFilters}>
              Clear
            </button>
          </div>
        </div>
      </div>

      {sessions.error && <ErrorBanner message={sessions.error} onRetry={sessions.refresh} />}
      {sessions.loading && !data && <Loading label="Loading sessions…" />}

      {data && data.content.length === 0 && (
        <Empty message="No sessions match these filters. Try clearing them, or seed demo data from the Overview page." />
      )}

      {data && data.content.length > 0 && (
        <div className="card table-card">
          <table className="table">
            <thead>
              <tr>
                <th>Started</th>
                <th>Agent</th>
                <th>Model</th>
                <th>Summary</th>
                <th className="num">Events</th>
                <th className="num">Errors</th>
                <th className="num">Cost</th>
                <th>Duration</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {data.content.map((s) => (
                <tr
                  key={s.id}
                  className="row-clickable"
                  onClick={() => navigate(`#/sessions/${s.id}`)}
                >
                  <td className="mono nowrap">{formatDateTime(s.startedAt)}</td>
                  <td className="mono">{truncate(s.agentType, 20)}</td>
                  <td className="mono">{truncate(s.model, 20)}</td>
                  <td title={s.summary}>{truncate(s.summary, 60)}</td>
                  <td className="num">{formatInt(s.stats.eventCount)}</td>
                  <td className="num">{s.stats.errorCount > 0 ? (
                    <span className="err-num">{formatInt(s.stats.errorCount)}</span>
                  ) : (
                    formatInt(s.stats.errorCount)
                  )}</td>
                  <td className="num">{formatCost(s.stats.costUsd)}</td>
                  <td className="nowrap">{formatDuration(s.stats.durationMs)}</td>
                  <td>
                    <StatusBadge status={s.status} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pagination
            page={data.page}
            totalPages={data.totalPages}
            totalElements={data.totalElements}
            onPage={(p) => {
              setPage(p);
              sessions.refresh();
            }}
          />
        </div>
      )}
    </div>
  );
}
