import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { tokenStore } from '../api/client';
import { jsonResponse, renderWithProviders } from '../test/helpers';
import { PlansPage } from './PlansPage';

const fetchMock = vi.fn();
const emptyPage = { items: [], page: 0, size: 50, totalItems: 0, totalPages: 0 };

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  tokenStore.set('t');
});
afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
});

function serve(role: string) {
  fetchMock.mockImplementation((url: string) => {
    if (url.includes('/auth/me')) return Promise.resolve(jsonResponse(200, { id: '1', email: 'a@x.test', name: 'A', role, status: 'ACTIVE' }));
    if (url.includes('/teams')) return Promise.resolve(jsonResponse(200, []));
    return Promise.resolve(jsonResponse(200, emptyPage));
  });
}

describe('PlansPage', () => {
  it('shows the empty state and the create form to an organizer', async () => {
    serve('ORGANIZER');
    renderWithProviders(<PlansPage />);
    expect(await screen.findByText(/No plans yet/)).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Create plan' })).toBeInTheDocument();
  });

  it('hides the create form from a viewer', async () => {
    serve('VIEWER');
    renderWithProviders(<PlansPage />);
    expect(await screen.findByText(/No plans yet/)).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Create plan' })).not.toBeInTheDocument();
  });

  it('blocks submitting a plan without a title and sends nothing', async () => {
    serve('ADMIN');
    renderWithProviders(<PlansPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Create plan' }));
    expect(await screen.findByText('Title is required')).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === 'POST')).toBe(false);
  });
});
