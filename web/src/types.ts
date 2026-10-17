/**
 * Types mirrored 1:1 from docs/API_CONTRACT.md — do not add fields here that
 * the server does not return.
 */

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type SessionStatus = "running" | "completed" | "failed" | "interrupted";

export interface SessionStats {
  eventCount: number;
  errorCount: number;
  toolCallCount: number;
  costUsd: number;
  durationMs: number;
}

export interface Session {
  id: string;
  agentType: string;
  agentVersion: string;
  model: string;
  cwd: string;
  status: SessionStatus;
  startedAt: string;
  endedAt: string | null;
  summary: string;
  metadata: Record<string, unknown>;
  stats: SessionStats;
}

export type EventType =
  | "session_start"
  | "session_end"
  | "tool_call"
  | "message"
  | "error"
  | "handoff"
  | "file_change"
  | "cost"
  | "approval"
  | "checkpoint"
  | "custom";

export const EVENT_TYPES: EventType[] = [
  "session_start",
  "session_end",
  "tool_call",
  "message",
  "error",
  "handoff",
  "file_change",
  "cost",
  "approval",
  "checkpoint",
  "custom",
];

export interface TrailEvent {
  id: string;
  sessionId: string;
  seq: number;
  ts: string;
  type: EventType;
  actor: string;
  name: string;
  summary: string;
  detail: unknown;
  durationMs: number;
  tokensIn: number;
  tokensOut: number;
  costUsd: number;
  parentEventId: string | null;
  tags: string[];
}

export interface OverviewStats {
  sessions: number;
  events: number;
  toolCalls: number;
  errors: number;
  totalCostUsd: number;
  totalTokensIn: number;
  totalTokensOut: number;
  avgSessionDurationMs: number;
  topTools: { name: string; count: number }[];
  errorsByType: { name: string; count: number }[];
  costByDay: { day: string; costUsd: number }[];
}

export interface SeedResult {
  sessionId: string;
}
