export function formatCost(usd: number | undefined | null): string {
  if (usd === undefined || usd === null || Number.isNaN(usd)) return "$0.00";
  return `$${usd.toFixed(2)}`;
}

export function formatCompact(n: number | undefined | null): string {
  if (n === undefined || n === null || Number.isNaN(n)) return "0";
  return new Intl.NumberFormat("en-US", { notation: "compact", maximumFractionDigits: 1 }).format(n);
}

export function formatInt(n: number | undefined | null): string {
  if (n === undefined || n === null || Number.isNaN(n)) return "0";
  return new Intl.NumberFormat("en-US").format(n);
}

export function formatDuration(ms: number | undefined | null): string {
  if (ms === undefined || ms === null || Number.isNaN(ms)) return "—";
  const s = Math.floor(ms / 1000);
  if (s < 1) return `${ms}ms`;
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${s % 60}s`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ${m % 60}m`;
  return `${Math.floor(h / 24)}d ${h % 24}h`;
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
}

export function truncate(s: string | null | undefined, max = 80): string {
  if (!s) return "";
  return s.length > max ? `${s.slice(0, max - 1)}…` : s;
}
