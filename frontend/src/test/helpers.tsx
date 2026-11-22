import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { AuthProvider } from '../auth/AuthContext';
import type { User } from '../api/types';

/** Builds a fetch Response with a JSON body, the way our backend answers. */
export function jsonResponse(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

export function apiErrorBody(status: number, code: string, message: string, extra: object = {}) {
  return { timestamp: '2026-01-01T00:00:00Z', status, code, message, path: '/x', correlationId: 'abc-12345', fieldErrors: [], ...extra };
}

export const admin: User = { id: '1', email: 'admin@x.test', name: 'Ada Admin', role: 'ADMIN', status: 'ACTIVE' };
export const viewer: User = { id: '2', email: 'view@x.test', name: 'Vic Viewer', role: 'VIEWER', status: 'ACTIVE' };

/** Renders a component inside the router and auth providers it needs. */
export function renderWithProviders(ui: ReactElement, initialPath = '/') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <AuthProvider>{ui}</AuthProvider>
    </MemoryRouter>,
  );
}
