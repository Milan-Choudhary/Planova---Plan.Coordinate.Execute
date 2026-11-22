import { useState } from 'react';
import { Link } from 'react-router-dom';
import { apiFetch } from '../api/client';
import { messageOf, useLoad } from '../api/useLoad';
import type { AppNotification, PageResponse } from '../api/types';
import { fmt, LoadState } from '../components/LoadState';

export function NotificationsPage() {
  const load = useLoad<PageResponse<AppNotification>>('/api/v1/notifications?size=50');
  const [error, setError] = useState<string | null>(null);

  async function markAll() {
    setError(null);
    try {
      await apiFetch('/api/v1/notifications/read-all', { method: 'POST' });
    } catch (err) {
      setError(messageOf(err));
    }
    load.reload();
  }
  async function markOne(id: string) {
    try {
      await apiFetch(`/api/v1/notifications/${id}/read`, { method: 'POST' });
    } catch (err) {
      setError(messageOf(err));
    }
    load.reload();
  }
  return (
    <section className="card">
      <h1>Notifications</h1>
      {error && <div className="alert alert-error" role="alert">{error}</div>}
      <button type="button" className="btn btn-secondary" onClick={() => void markAll()}>Mark all as read</button>
      <LoadState load={load} what="notifications">
        {(page) => page.items.length === 0 ? <p className="muted">Nothing here yet.</p> : (
          <ul>
            {page.items.map((n) => (
              <li key={n.id} className={n.readAt ? undefined : 'unread'}>
                {n.incidentId ? <Link to={`/incidents/${n.incidentId}`}>{n.title}</Link> : n.title}
                {' '}<span className="muted">{fmt(n.createdAt)}</span>
                {n.body && <div className="muted">{n.body}</div>}
                {!n.readAt && <button type="button" className="btn btn-secondary" onClick={() => void markOne(n.id)}>Mark read</button>}
              </li>
            ))}
          </ul>
        )}
      </LoadState>
    </section>
  );
}
