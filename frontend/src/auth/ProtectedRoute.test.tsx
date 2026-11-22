import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { tokenStore } from '../api/client';
import { admin, jsonResponse, renderWithProviders, viewer } from '../test/helpers';
import { ProtectedRoute } from './ProtectedRoute';

const fetchMock = vi.fn();

function renderApp(path: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/login" element={<div>LOGIN PAGE</div>} />
      <Route path="/secret" element={<ProtectedRoute><div>SECRET</div></ProtectedRoute>} />
      <Route path="/admin" element={<ProtectedRoute roles={['ADMIN']}><div>ADMIN ONLY</div></ProtectedRoute>} />
    </Routes>,
    path,
  );
}

beforeEach(() => vi.stubGlobal('fetch', fetchMock));
afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
});

describe('ProtectedRoute', () => {
  it('redirects anonymous visitors to the login page', async () => {
    renderApp('/secret');
    expect(await screen.findByText('LOGIN PAGE')).toBeInTheDocument();
    expect(screen.queryByText('SECRET')).not.toBeInTheDocument();
  });

  it('restores a session from a stored token by asking the server who the user is', async () => {
    tokenStore.set('stored-token');
    fetchMock.mockResolvedValue(jsonResponse(200, viewer));

    renderApp('/secret');

    expect(await screen.findByText('SECRET')).toBeInTheDocument();
    expect(fetchMock.mock.calls[0][0]).toContain('/api/v1/auth/me');
  });

  it('drops an invalid stored token and sends the user to login', async () => {
    tokenStore.set('expired-token');
    fetchMock.mockResolvedValue(jsonResponse(401, { code: 'AUTHENTICATION_REQUIRED', message: 'expired' }));

    renderApp('/secret');

    expect(await screen.findByText('LOGIN PAGE')).toBeInTheDocument();
    expect(tokenStore.get()).toBeNull();
  });

  it('shows "Not authorized" when the role is not allowed', async () => {
    tokenStore.set('t');
    fetchMock.mockResolvedValue(jsonResponse(200, viewer));

    renderApp('/admin');

    expect(await screen.findByRole('alert')).toHaveTextContent('Not authorized');
    expect(screen.queryByText('ADMIN ONLY')).not.toBeInTheDocument();
  });

  it('lets an allowed role in', async () => {
    tokenStore.set('t');
    fetchMock.mockResolvedValue(jsonResponse(200, admin));

    renderApp('/admin');

    expect(await screen.findByText('ADMIN ONLY')).toBeInTheDocument();
  });
});
