/**
 * Shared API client for the AgentTrail dashboard.
 * - Attaches the `X-API-Key` header to every authenticated request.
 * - Unwraps the pagination envelope (`Page<T>` -> caller gets the page; helpers return content).
 * - Normalizes HTTP errors into readable ApiError messages (401/404/409 handled explicitly).
 */

import type { OverviewStats, Page, Session, SeedResult, TrailEvent } from "./types";

const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:8080";
const API_KEY = import.meta.env.VITE_API_KEY ?? "dev-key";

export class ApiError extends Error {
  status: number;
  code: string;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

interface ErrorBody {
  error?: string;
  message?: string;
}

async function parseError(res: Response): Promise<ApiError> {
  let code = "request_failed";
  let message = `Request failed with status ${res.status}`;
  try {
    const body = (await res.json()) as ErrorBody;
    if (body.error) code = body.error;
    if (body.message) message = body.message;
  } catch {
    // keep the fallback message
  }
  return new ApiError(res.status, code, message);
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const url = `${API_URL}${path}`;
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body !== undefined) headers.set("Content-Type", "application/json");
  headers.set("X-API-Key", API_KEY);

  let res: Response;
  try {
    res = await fetch(url, { ...init, headers });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    throw new ApiError(0, "network_error", `Could not reach the API at ${API_URL} (${msg})`);
  }

  if (!res.ok) throw await parseError(res);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

type QueryValue = string | number | boolean | (string | number)[] | undefined | null;

function buildQuery(params: Record<string, QueryValue> | object): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params as Record<string, QueryValue>)) {
    if (value === undefined || value === null || value === "") continue;
    if (Array.isArray(value)) {
      for (const v of value) search.append(key, String(v));
    } else {
      search.append(key, String(value));
    }
  }
  const qs = search.toString();
  return qs ? `?${qs}` : "";
}

export interface SessionFilters {
  agentType?: string;
  status?: string;
  q?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export interface EventFilters {
  type?: string[];
  actor?: string;
  q?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export interface StatsParams {
  from?: string;
  to?: string;
  agentType?: string;
  page?: number;
  size?: number;
}

export const api = {
  getOverview(params: StatsParams = {}): Promise<OverviewStats> {
    return request<OverviewStats>(`/api/v1/stats/overview${buildQuery(params)}`);
  },

  getRecentErrors(params: StatsParams = {}): Promise<Page<TrailEvent>> {
    return request<Page<TrailEvent>>(`/api/v1/stats/errors${buildQuery(params)}`);
  },

  getSessions(filters: SessionFilters = {}): Promise<Page<Session>> {
    return request<Page<Session>>(`/api/v1/sessions${buildQuery(filters)}`);
  },

  getSession(id: string): Promise<Session> {
    return request<Session>(`/api/v1/sessions/${encodeURIComponent(id)}`);
  },

  getEvents(sessionId: string, filters: EventFilters = {}): Promise<Page<TrailEvent>> {
    return request<Page<TrailEvent>>(
      `/api/v1/sessions/${encodeURIComponent(sessionId)}/events${buildQuery(filters)}`,
    );
  },

  seedDemo(): Promise<SeedResult> {
    return request<SeedResult>(`/api/v1/demo/seed`, { method: "POST" });
  },
};

/** Turn an ApiError into a human-readable banner line. */
export function apiErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0:
        return err.message; // network error already readable
      case 401:
        return `Unauthorized (401): ${err.message}. Check VITE_API_KEY against the server's AGENTTRAIL_API_KEY.`;
      case 404:
        return `Not found (404): ${err.message}`;
      case 409:
        return `Conflict (409): ${err.message}`;
      default:
        return `Request failed (${err.status}): ${err.message}`;
    }
  }
  return err instanceof Error ? err.message : "An unexpected error occurred.";
}
