import { useState } from "react";
import { api, apiErrorMessage } from "../api";
import type { OverviewStats } from "../types";
import { formatCompact, formatCost, formatDateTime, formatDuration, formatInt, truncate } from "../format";
import { Empty, ErrorBanner, Loading, StatCard } from "../components/ui";
import { useApi } from "../components/hooks";
import { navigate } from "../components/router";

function CostChart({ data }: { data: { day: string; costUsd: number }[] }) {
  const W = 640;
  const H = 170;
  const PAD = { l: 44, r: 8, t: 12, b: 26 };
  const innerW = W - PAD.l - PAD.r;
  const innerH = H - PAD.t - PAD.b;
  const max = Math.max(...data.map((d) => d.costUsd), 0);
  const n = data.length;
  const slot = innerW / Math.max(n, 1);
  const barW = Math.min(28, Math.max(4, slot * 0.62));

  const yTicks = 4;
  const ticks = Array.from({ length: yTicks + 1 }, (_, i) => (max * i) / yTicks);

  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="chart" role="img" aria-label="Cost by day">
      {ticks.map((t) => {
        const y = PAD.t + innerH - (max > 0 ? (t / max) * innerH : 0);
        return (
          <g key={t}>
            <line x1={PAD.l} y1={y} x2={W - PAD.r} y2={y} className="grid-line" />
            <text x={PAD.l - 6} y={y + 4} textAnchor="end" className="tick-label">
              ${t.toFixed(2)}
            </text>
          </g>
        );
      })}
      {data.map((d, i) => {
        const h = max > 0 ? (d.costUsd / max) * innerH : 0;
        const x = PAD.l + i * slot + (slot - barW) / 2;
        const y = PAD.t + innerH - h;
        return (
          <g key={d.day}>
            <title>{`${d.day}: ${formatCost(d.costUsd)}`}</title>
            <rect x={x} y={y} width={barW} height={Math.max(h, 1.5)} rx={3} className="bar" />
            {n <= 31 && (
              <text
                x={x + barW / 2}
                y={H - 8}
                textAnchor="middle"
                className="tick-label"
                transform={`rotate(-35 ${x + barW / 2} ${H - 8})`}
              >
                {d.day.slice(5)}
              </text>
            )}
          </g>
        );
      })}
    </svg>
  );
}

export default function Overview() {
  const [seeding, setSeeding] = useState(false);
  const [seedMsg, setSeedMsg] = useState<string | null>(null);
  const stats = useApi<OverviewStats>(() => api.getOverview({}), "overview");

  const overview = stats.data;
  const errors = useApi(
    () => api.getRecentErrors({ size: 10 }),
    "overview-errors",
  );

  async function handleSeed() {
    setSeeding(true);
    setSeedMsg(null);
    try {
      const res = await api.seedDemo();
      setSeedMsg(`Seeded demo session ${truncate(res.sessionId, 12)}…`);
      stats.refresh();
      errors.refresh();
    } catch (e) {
      setSeedMsg(`Seed failed: ${apiErrorMessage(e)}`);
    } finally {
      setSeeding(false);
    }
  }

  return (
    <div className="page">
      <div className="page-head">
        <h1>Overview</h1>
        <div className="page-head-actions">
          {seedMsg && <span className="hint">{seedMsg}</span>}
          <button className="btn" onClick={handleSeed} disabled={seeding}>
            {seeding ? "Seeding…" : "Seed demo data"}
          </button>
        </div>
      </div>

      {stats.error && <ErrorBanner message={stats.error} onRetry={stats.refresh} />}
      {stats.loading && !overview && <Loading label="Loading overview…" />}

      {overview && (
        <>
          <section className="stat-grid">
            <StatCard label="Sessions" value={formatInt(overview.sessions)} />
            <StatCard label="Events" value={formatCompact(overview.events)} />
            <StatCard label="Tool calls" value={formatCompact(overview.toolCalls)} />
            <StatCard
              label="Errors"
              value={formatInt(overview.errors)}
              sub={
                overview.events > 0
                  ? `${((overview.errors / overview.events) * 100).toFixed(1)}% of events`
                  : undefined
              }
            />
            <StatCard label="Total cost" value={formatCost(overview.totalCostUsd)} />
            <StatCard
              label="Tokens in / out"
              value={
                <>
                  {formatCompact(overview.totalTokensIn)}
                  <span className="stat-slash"> / </span>
                  {formatCompact(overview.totalTokensOut)}
                </>
              }
            />
            <StatCard label="Avg session duration" value={formatDuration(overview.avgSessionDurationMs)} />
          </section>

          <section className="grid-2">
            <div className="card">
              <h2>Cost by day</h2>
              {overview.costByDay.length === 0 ? (
                <Empty message="No cost data yet." />
              ) : (
                <CostChart data={overview.costByDay} />
              )}
            </div>
            <div className="card">
              <h2>Top tools</h2>
              {overview.topTools.length === 0 ? (
                <Empty message="No tool calls recorded." />
              ) : (
                <ul className="rank-list">
                  {overview.topTools.map((t) => (
                    <li key={t.name}>
                      <span className="rank-name">{t.name}</span>
                      <span className="rank-bar-track">
                        <span
                          className="rank-bar"
                          style={{
                            width: `${(t.count / (overview.topTools[0]?.count ?? 1)) * 100}%`,
                          }}
                        />
                      </span>
                      <span className="rank-count">{formatInt(t.count)}</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </section>

          <section className="grid-2">
            <div className="card">
              <h2>Errors by tool</h2>
              {overview.errorsByType.length === 0 ? (
                <Empty message="No errors recorded. Clean run." />
              ) : (
                <ul className="rank-list">
                  {overview.errorsByType.map((t) => (
                    <li key={t.name}>
                      <span className="rank-name">{t.name}</span>
                      <span className="rank-bar-track error">
                        <span
                          className="rank-bar error"
                          style={{
                            width: `${(t.count / (overview.errorsByType[0]?.count ?? 1)) * 100}%`,
                          }}
                        />
                      </span>
                      <span className="rank-count">{formatInt(t.count)}</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
            <div className="card">
              <h2>Recent error events</h2>
              {errors.error && <ErrorBanner message={errors.error} onRetry={errors.refresh} />}
              {errors.loading && !errors.data && <Loading label="Loading errors…" />}
              {errors.data && errors.data.content.length === 0 && (
                <Empty message="No error events yet." />
              )}
              {errors.data && errors.data.content.length > 0 && (
                <table className="table">
                  <thead>
                    <tr>
                      <th>Time</th>
                      <th>Event</th>
                      <th>Session</th>
                    </tr>
                  </thead>
                  <tbody>
                    {errors.data.content.map((e) => (
                      <tr
                        key={e.id}
                        className="row-clickable"
                        onClick={() => navigate(`#/sessions/${e.sessionId}`)}
                      >
                        <td className="mono nowrap">{formatDateTime(e.ts)}</td>
                        <td>{truncate(e.name || e.summary)}</td>
                        <td className="mono">{truncate(e.sessionId, 12)}…</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </section>
        </>
      )}
    </div>
  );
}
