import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiFetch, ApiError, setUnauthorizedHandler, tokenStore } from './client';
import { apiErrorBody, jsonResponse } from '../test/helpers';

const fetchMock = vi.fn();

/** Awaits a promise that must reject; fails the test if it resolves. Returns the ApiError. */
async function rejection(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (e) {
    return e as ApiError;
  }
  throw new Error('expected the call to reject, but it resolved');
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
  setUnauthorizedHandler(null);
});

describe('apiFetch', () => {
  it('sends the bearer token when logged in', async () => {
    tokenStore.set('tok-123');
    fetchMock.mockResolvedValue(jsonResponse(200, { ok: true }));

    await apiFetch('/api/v1/auth/me');

    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers.Authorization).toBe('Bearer tok-123');
  });

  it('does NOT send the token when auth is false (login call)', async () => {
    tokenStore.set('tok-123');
    fetchMock.mockResolvedValue(jsonResponse(200, {}));

    await apiFetch('/api/v1/auth/login', { method: 'POST', body: { a: 1 }, auth: false });

    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers.Authorization).toBeUndefined();
    expect(init.headers['Content-Type']).toBe('application/json');
    expect(init.body).toBe('{"a":1}');
  });

  it('turns the standard backend error body into an ApiError', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(400, apiErrorBody(400, 'VALIDATION_FAILED', 'Request validation failed', {
        fieldErrors: [{ field: 'email', message: 'must be a well-formed email address' }],
      })),
    );

    const err = await rejection(apiFetch('/api/v1/admin/users', { method: 'POST', body: {} }));

    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(400);
    expect(err.code).toBe('VALIDATION_FAILED');
    expect(err.fieldErrors).toEqual([{ field: 'email', message: 'must be a well-formed email address' }]);
    expect(err.correlationId).toBe('abc-12345');
  });

  it('reports a network failure as ApiError status 0 with a friendly message', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    const err = await rejection(apiFetch('/api/v1/auth/me'));

    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(0);
    expect(err.code).toBe('NETWORK_ERROR');
  });

  it('survives a non-JSON error body (e.g. a proxy error page)', async () => {
    fetchMock.mockResolvedValue(new Response('<html>Bad gateway</html>', { status: 502 }));

    const err = await rejection(apiFetch('/api/v1/auth/me'));

    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(502);
    expect(err.code).toBe('HTTP_502');
  });

  it('calls the unauthorized handler on a 401 for authenticated calls', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    fetchMock.mockResolvedValue(jsonResponse(401, apiErrorBody(401, 'AUTHENTICATION_REQUIRED', 'expired')));

    await apiFetch('/api/v1/auth/me').catch(() => undefined);

    expect(handler).toHaveBeenCalledOnce();
  });

  it('does NOT call the unauthorized handler for a failed login (401 = wrong password)', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    fetchMock.mockResolvedValue(jsonResponse(401, apiErrorBody(401, 'INVALID_CREDENTIALS', 'Invalid email or password')));

    const err = await rejection(apiFetch('/api/v1/auth/login', { method: 'POST', body: {}, auth: false }));

    expect(handler).not.toHaveBeenCalled();
    expect(err.code).toBe('INVALID_CREDENTIALS');
  });

  it('returns undefined for 204 No Content', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await expect(apiFetch('/api/v1/x', { method: 'DELETE' })).resolves.toBeUndefined();
  });
});
