import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { jsonResponse, renderWithProviders } from '../test/helpers';
import { LoginPage } from './LoginPage';
import { SignupPage } from './SignupPage';

afterEach(() => vi.unstubAllGlobals());

it('registers through the public API and directs the user to sign in', async () => {
  const request = vi.fn().mockResolvedValue(jsonResponse(201, { role: 'VIEWER' }));
  vi.stubGlobal('fetch', request);
  renderWithProviders(
    <Routes>
      <Route path="/signup" element={<SignupPage />} />
      <Route path="/login" element={<LoginPage />} />
    </Routes>,
    '/signup',
  );

  const user = userEvent.setup();
  await user.type(screen.getByLabelText('Name'), 'New Viewer');
  await user.type(screen.getByLabelText('Email'), 'viewer@example.test');
  await user.type(screen.getByLabelText('Password'), 'test-only-password');
  await user.click(screen.getByRole('button', { name: 'Sign up' }));

  expect(await screen.findByText('Account created. Sign in to continue.')).toBeInTheDocument();
  const [url, options] = request.mock.calls[0] as [string, RequestInit];
  expect(url).toContain('/api/v1/auth/register');
  expect(options.method).toBe('POST');
  expect(JSON.parse(String(options.body))).toEqual({
    name: 'New Viewer', email: 'viewer@example.test', password: 'test-only-password', role: 'TEAM_MEMBER',
  });
});
