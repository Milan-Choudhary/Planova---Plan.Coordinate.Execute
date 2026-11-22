import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { tokenStore } from '../api/client';
import { apiErrorBody, jsonResponse, renderWithProviders, viewer } from '../test/helpers';
import { LoginPage } from './LoginPage';

const fetchMock = vi.fn();

function renderLogin() {
  return renderWithProviders(
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/" element={<div>HOME PAGE</div>} />
    </Routes>,
    '/login',
  );
}

beforeEach(() => vi.stubGlobal('fetch', fetchMock));
afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
});

describe('LoginPage', () => {
  it('shows validation messages and does not call the API when fields are empty', async () => {
    renderLogin();
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(screen.getByText('Enter your email address')).toBeInTheDocument();
    expect(screen.getByText('Enter your password')).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('logs in, stores the token and navigates home on success', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, { accessToken: 'jwt-abc', tokenType: 'Bearer', expiresInSeconds: 3600, user: viewer }),
    );
    renderLogin();

    await userEvent.type(screen.getByLabelText('Email'), 'view@x.test');
    await userEvent.type(screen.getByLabelText('Password'), 'correct-horse-battery');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('HOME PAGE')).toBeInTheDocument();
    expect(tokenStore.get()).toBe('jwt-abc');
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toContain('/api/v1/auth/login');
    expect(JSON.parse(init.body)).toEqual({ email: 'view@x.test', password: 'correct-horse-battery' });
  });

  it('shows the server message on wrong credentials, stays on the page and clears the password', async () => {
    fetchMock.mockResolvedValue(jsonResponse(401, apiErrorBody(401, 'INVALID_CREDENTIALS', 'Invalid email or password')));
    renderLogin();

    await userEvent.type(screen.getByLabelText('Email'), 'view@x.test');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong-password');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password');
    expect(screen.queryByText('HOME PAGE')).not.toBeInTheDocument();
    expect(tokenStore.get()).toBeNull();
    expect(screen.getByLabelText('Password')).toHaveValue('');
  });

  it('shows a friendly message when the server is unreachable', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    renderLogin();

    await userEvent.type(screen.getByLabelText('Email'), 'a@x.test');
    await userEvent.type(screen.getByLabelText('Password'), 'pw');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the server');
  });

  it('shows the correlation id for server (5xx) errors so they can be reported', async () => {
    fetchMock.mockResolvedValue(jsonResponse(500, apiErrorBody(500, 'INTERNAL_ERROR', 'An unexpected error occurred.')));
    renderLogin();

    await userEvent.type(screen.getByLabelText('Email'), 'a@x.test');
    await userEvent.type(screen.getByLabelText('Password'), 'pw');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('abc-12345'));
  });

  it('disables the button while the request is in flight (prevents double submit)', async () => {
    let resolve!: (r: Response) => void;
    fetchMock.mockReturnValue(new Promise<Response>((r) => (resolve = r)));
    renderLogin();

    await userEvent.type(screen.getByLabelText('Email'), 'a@x.test');
    await userEvent.type(screen.getByLabelText('Password'), 'pw');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(screen.getByRole('button', { name: 'Signing in…' })).toBeDisabled();
    resolve(jsonResponse(401, apiErrorBody(401, 'INVALID_CREDENTIALS', 'Invalid email or password')));
    await screen.findByRole('alert');
  });
});
