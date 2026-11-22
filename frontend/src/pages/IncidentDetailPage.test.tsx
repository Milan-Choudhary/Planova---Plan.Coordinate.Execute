import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { tokenStore } from '../api/client';
import type { Incident } from '../api/types';
import { apiErrorBody, jsonResponse, renderWithProviders } from '../test/helpers';
import { IncidentDetailPage } from './IncidentDetailPage';

const fetchMock = vi.fn();

const base: Incident = {
  id: 'inc-1', planId: 'plan-1', planTitle: 'Summer Fest', title: 'Stage lights down', description: 'No power',
  severity: 'HIGH', status: 'ASSIGNED', reporterId: 'u9', reporterName: 'Rita Reporter', assigneeId: '2',
  assigneeName: 'Vic', ackDeadline: '2030-01-01T00:00:00Z', ackOverdue: false, escalationLevel: 0,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', version: 3,
  allowedActions: ['ACKNOWLEDGE', 'REJECT'],
};
const history = { assignments: [], audit: [] };

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  tokenStore.set('t');
});
afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
});

function renderPage() {
  return renderWithProviders(
    <Routes><Route path="/incidents/:incidentId" element={<IncidentDetailPage />} /></Routes>,
    '/incidents/inc-1',
  );
}

describe('IncidentDetailPage', () => {
  it('shows only the buttons the server allows', async () => {
    fetchMock.mockImplementation((url: string) =>
      Promise.resolve(jsonResponse(200, url.endsWith('/history') ? history : base)));
    renderPage();

    expect(await screen.findByRole('button', { name: 'Acknowledge' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reject (pass on)' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Resolve' })).not.toBeInTheDocument();
  });

  it('sends the expected version, then re-reads the incident after acting', async () => {
    let current = base;
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        current = { ...base, status: 'ACKNOWLEDGED', version: 4, allowedActions: ['START', 'RESOLVE'] };
        return Promise.resolve(jsonResponse(200, current));
      }
      return Promise.resolve(jsonResponse(200, url.endsWith('/history') ? history : current));
    });
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Acknowledge' }));

    expect(await screen.findByRole('button', { name: 'Start work' })).toBeInTheDocument();
    const post = fetchMock.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === 'POST');
    expect(post?.[0]).toContain('/api/v1/incidents/inc-1/acknowledge');
    expect(JSON.parse((post?.[1] as RequestInit).body as string).expectedVersion).toBe(3);
  });

  it('explains a 409 (escalated past you) and refreshes to the true state', async () => {
    let escalated = false;
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        escalated = true;
        return Promise.resolve(jsonResponse(409, apiErrorBody(409, 'NOT_CURRENT_ASSIGNEE',
          'This incident is no longer assigned to you (it was escalated or reassigned)')));
      }
      if (url.endsWith('/history')) return Promise.resolve(jsonResponse(200, history));
      return Promise.resolve(jsonResponse(200, escalated
        ? { ...base, assigneeName: 'Someone else', assigneeId: '3', allowedActions: [] } : base));
    });
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Acknowledge' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('no longer assigned to you');
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Acknowledge' })).not.toBeInTheDocument());
  });

  it('shows an error with retry when the incident cannot be loaded', async () => {
    fetchMock.mockResolvedValue(jsonResponse(404, apiErrorBody(404, 'INCIDENT_NOT_FOUND', 'Incident not found')));
    renderPage();
    expect(await screen.findAllByText('Incident not found')).not.toHaveLength(0);
    expect(screen.getAllByRole('button', { name: 'Retry' }).length).toBeGreaterThan(0);
  });
});
