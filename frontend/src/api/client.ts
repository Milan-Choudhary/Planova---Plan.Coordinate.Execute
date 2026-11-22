// One place that talks to the backend: base URL, bearer token, and uniform error handling.

export interface FieldViolation {
  field: string;
  message: string;
}

/** Thrown for EVERY failed call (HTTP error or network failure) so UI code has one case to handle. */
export class ApiError extends Error {
  constructor(
    public readonly status: number, // 0 = the request never reached the server
    public readonly code: string, // stable machine-readable code, e.g. VALIDATION_FAILED
    message: string,
    public readonly fieldErrors: FieldViolation[] = [],
    public readonly correlationId?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

// Empty string => same-origin relative URLs (Vite dev proxy or the nginx proxy in Docker).
const BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? '';

// ---- token storage -------------------------------------------------------------------------
// sessionStorage: survives a page refresh but is wiped when the tab closes. Trade-off (documented
// in DECISIONS.md D-006): readable by injected scripts (XSS), unlike an httpOnly cookie. Every
// access is wrapped in try/catch because browsers can block storage (private mode, policies).
const TOKEN_KEY = 'planova.token';

export const tokenStore = {
  get(): string | null {
    try {
      return sessionStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  },
  set(token: string): void {
    try {
      sessionStorage.setItem(TOKEN_KEY, token);
    } catch {
      /* storage unavailable: the user simply has to log in again after a refresh */
    }
  },
  clear(): void {
    try {
      sessionStorage.removeItem(TOKEN_KEY);
    } catch {
      /* ignore */
    }
  },
};

// Lets the auth layer react when the server says "your token is no longer valid".
let unauthorizedHandler: (() => void) | null = null;
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler;
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  body?: unknown;
  /** false for the login call: a 401 there means "wrong password", not "session expired". */
  auth?: boolean;
  signal?: AbortSignal;
  /** Extra request headers, e.g. Idempotency-Key. */
  headers?: Record<string, string>;
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, auth = true, signal, headers: extraHeaders } = options;

  const headers: Record<string, string> = { Accept: 'application/json', ...extraHeaders };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const token = tokenStore.get();
  if (auth && token) headers.Authorization = `Bearer ${token}`;

  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal,
    });
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
    throw new ApiError(0, 'NETWORK_ERROR', 'Cannot reach the server. Check your connection and try again.');
  }

  const text = response.status === 204 ? '' : await response.text();
  const data = text ? parseJson(text) : undefined;

  if (!response.ok) {
    if (response.status === 401 && auth) unauthorizedHandler?.();
    const err = (data ?? {}) as Partial<{
      code: string;
      message: string;
      fieldErrors: FieldViolation[];
      correlationId: string;
    }>;
    throw new ApiError(
      response.status,
      err.code ?? `HTTP_${response.status}`,
      err.message ?? 'The request failed. Please try again.',
      err.fieldErrors ?? [],
      err.correlationId ?? response.headers.get('X-Correlation-Id') ?? undefined,
    );
  }
  return data as T;
}

/** A fresh random key for the Idempotency-Key header (crypto.randomUUID where available). */
export function newIdempotencyKey(): string {
  const c = globalThis.crypto as Crypto | undefined;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
}
