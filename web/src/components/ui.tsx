import type { ReactNode } from "react";
import { navigate } from "./router";

export function Nav({ routeName }: { routeName: string }) {
  const link = (to: string, label: string, active: boolean) => (
    <button
      key={to}
      className={`nav-link${active ? " active" : ""}`}
      onClick={() => navigate(to)}
    >
      {label}
    </button>
  );
  return (
    <header className="topbar">
      <button className="brand" onClick={() => navigate("#/")}>
        <span className="brand-mark">▮</span> AgentTrail
      </button>
      <nav className="nav-links">
        {link("#/", "Overview", routeName === "overview")}
        {link("#/sessions", "Sessions", routeName === "sessions" || routeName === "sessionDetail")}
      </nav>
    </header>
  );
}

export function ErrorBanner({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="error-banner" role="alert">
      <span className="error-icon">⚠</span>
      <span className="error-text">{message}</span>
      {onRetry && (
        <button className="btn btn-small" onClick={onRetry}>
          Retry
        </button>
      )}
    </div>
  );
}

export function Loading({ label = "Loading…" }: { label?: string }) {
  return (
    <div className="state-box">
      <div className="spinner" aria-hidden="true" />
      <p>{label}</p>
    </div>
  );
}

export function Empty({ message = "Nothing here yet." }: { message?: string }) {
  return (
    <div className="state-box">
      <div className="empty-icon" aria-hidden="true">
        ◌
      </div>
      <p>{message}</p>
    </div>
  );
}

export function StatCard({ label, value, sub }: { label: string; value: ReactNode; sub?: ReactNode }) {
  return (
    <div className="stat-card">
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {sub !== undefined && <div className="stat-sub">{sub}</div>}
    </div>
  );
}

const STATUS_CLASS: Record<string, string> = {
  running: "badge-running",
  completed: "badge-completed",
  failed: "badge-failed",
  interrupted: "badge-interrupted",
};

export function StatusBadge({ status }: { status: string }) {
  const cls = STATUS_CLASS[status] ?? "badge-default";
  return <span className={`badge ${cls}`}>{status}</span>;
}

const TYPE_CLASS: Record<string, string> = {
  error: "badge-failed",
  tool_call: "badge-running",
  session_start: "badge-completed",
  session_end: "badge-default",
  handoff: "badge-interrupted",
  approval: "badge-interrupted",
};

export function TypeBadge({ type }: { type: string }) {
  const cls = TYPE_CLASS[type] ?? "badge-default";
  return <span className={`badge ${cls}`}>{type}</span>;
}

export function Pagination({
  page,
  totalPages,
  totalElements,
  onPage,
}: {
  page: number;
  totalPages: number;
  totalElements: number;
  onPage: (page: number) => void;
}) {
  if (totalElements === 0) return null;
  return (
    <div className="pagination">
      <button className="btn btn-small" disabled={page <= 0} onClick={() => onPage(page - 1)}>
        ← Prev
      </button>
      <span className="pagination-info">
        Page {page + 1} of {Math.max(totalPages, 1)} · {totalElements} total
      </span>
      <button
        className="btn btn-small"
        disabled={page + 1 >= totalPages}
        onClick={() => onPage(page + 1)}
      >
        Next →
      </button>
    </div>
  );
}
