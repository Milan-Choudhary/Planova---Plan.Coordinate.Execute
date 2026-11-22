import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { tokenStore } from '../api/client';
import { admin, apiErrorBody, jsonResponse, renderWithProviders, viewer } from '../test/helpers';
import { UsersAdminPage } from './UsersAdminPage';

const fetchMock = vi.fn();

const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalItems: items.length, totalPages: 1 });

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  tokenStore.set('t');
});
afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
});

// Routes the mocked fetch by URL so tests read like the real conversation with the backend.
function route(handlers: Record<string, () => Response | Promise<Response>>) {
  fetchMock.mockImplementation((url: string, init?: RequestInit) => {
    const key = `${init?.method ?? 'GET'} ${url.replace(/^https?:\/\/[^/]+/, '').split('?')[0]}`;
    const h = handlers[key];
    if (!h) throw new Error(`Unmocked request: ${key}`);
    return Promise.resolve(h());
  });
}

describe('UsersAdminPage', () => {
  it('shows a loading state, then the users', async () => {
    route({ 'GET /api/v1/admin/users': () => jsonResponse(200, page([admin, viewer])) });
    renderWithProviders(<UsersAdminPage />);

    expect(screen.getByText('Loading users…')).toBeInTheDocument();
    expect(await screen.findByText('view@x.test')).toBeInTheDocument();
    expect(screen.getByText('admin@x.test')).toBeInTheDocument();
  });

  it('shows an empty state when there are no users', async () => {
    route({ 'GET /api/v1/admin/users': () => jsonResponse(200, page([])) });
    renderWithProviders(<UsersAdminPage />);
    expect(await screen.findByText('No users yet.')).toBeInTheDocument();
  });

  it('shows the error and a working Retry button when loading fails', async () => {
    route({ 'GET /api/v1/admin/users': () => jsonResponse(403, apiErrorBody(403, 'FORBIDDEN', 'You do not have permission to perform this action')) });
    renderWithProviders(<UsersAdminPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have permission');

    route({ 'GET /api/v1/admin/users': () => jsonResponse(200, page([viewer])) });
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('view@x.test')).toBeInTheDocument();
  });

  it('creates a user, confirms success and reloads the list', async () => {
    const created = { id: '3', email: 'new@x.test', name: 'New One', role: 'TEAM_MEMBER', status: 'ACTIVE' };
    let listed = [admin];
    route({
      'GET /api/v1/admin/users': () => jsonResponse(200, page(listed)),
      'POST /api/v1/admin/users': () => {
        listed = [admin, created as typeof admin];
        return jsonResponse(201, created);
      },
    });
    renderWithProviders(<UsersAdminPage />);
    await screen.findByText('admin@x.test');

    await userEvent.type(screen.getByLabelText('Email'), 'new@x.test');
    await userEvent.type(screen.getByLabelText('Name'), 'New One');
    await userEvent.type(screen.getByLabelText(/Initial password/), 'a-long-enough-password');
    await userEvent.click(screen.getByRole('button', { name: 'Create user' }));

    expect(await screen.findByText('Created new@x.test')).toBeInTheDocument();
    expect(await screen.findByRole('cell', { name: 'new@x.test' })).toBeInTheDocument();
    const postCall = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')!;
    expect(JSON.parse(postCall[1].body)).toEqual({
      email: 'new@x.test', name: 'New One', role: 'TEAM_MEMBER', password: 'a-long-enough-password',
    });
  });

  it('maps server field errors onto the form', async () => {
    route({
      'GET /api/v1/admin/users': () => jsonResponse(200, page([admin])),
      'POST /api/v1/admin/users': () =>
        jsonResponse(400, apiErrorBody(400, 'VALIDATION_FAILED', 'Request validation failed', {
          fieldErrors: [{ field: 'email', message: 'must be a well-formed email address' }],
        })),
    });
    renderWithProviders(<UsersAdminPage />);
    await screen.findByText('admin@x.test');

    await userEvent.click(screen.getByRole('button', { name: 'Create user' }));

    expect(await screen.findByText('must be a well-formed email address')).toBeInTheDocument();
    expect(screen.getByText('Request validation failed')).toBeInTheDocument();
  });

  it('shows the server reason when a role change is refused, and re-syncs the list', async () => {
    let calls = 0;
    route({
      'GET /api/v1/admin/users': () => { calls += 1; return jsonResponse(200, page([admin, viewer])); },
      'PATCH /api/v1/admin/users/2': () =>
        jsonResponse(409, apiErrorBody(409, 'STALE_VERSION', 'This account was changed elsewhere')),
    });
    renderWithProviders(<UsersAdminPage />);
    await screen.findByText('view@x.test');

    const row = screen.getByRole('row', { name: /view@x.test/ });
    await userEvent.selectOptions(within(row).getByLabelText('Role for view@x.test'), 'ORGANIZER');

    expect(await screen.findByText('This account was changed elsewhere')).toBeInTheDocument();
    expect(calls).toBeGreaterThanOrEqual(2); // initial load + re-sync after the failure
  });
});
