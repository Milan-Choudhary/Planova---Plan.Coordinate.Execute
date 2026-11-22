import { useState } from 'react';
import { apiFetch } from '../api/client';
import { messageOf, useLoad } from '../api/useLoad';
import { useAuth } from '../auth/AuthContext';
import type { Team, TeamDetail } from '../api/types';
import { LoadState } from '../components/LoadState';

export function TeamsPage() {
  const { user } = useAuth();
  const teams = useLoad<Team[]>('/api/v1/teams');
  const [selected, setSelected] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (!name.trim()) return setError('Team name is required.');
    try {
      await apiFetch('/api/v1/teams', { method: 'POST', body: { name } });
      setName('');
      teams.reload();
    } catch (err) {
      setError(messageOf(err));
    }
  }
  return (
    <div className="stack">
      <section className="card">
        <h1>Teams</h1>
        <LoadState load={teams} what="teams">
          {(list) => list.length === 0 ? <p className="muted">No teams yet.</p> : (
            <ul>
              {list.map((t) => (
                <li key={t.id}>
                  <button type="button" className="btn btn-secondary" onClick={() => setSelected(t.id)}>{t.name}</button>{' '}
                  <span className="muted">{t.memberCount} members</span>
                </li>
              ))}
            </ul>
          )}
        </LoadState>
      </section>
      {selected && <TeamDetailCard id={selected} canAdmin={user?.role === 'ADMIN'} myId={user?.id} />}
      {(user?.role === 'ADMIN' || user?.role === 'ORGANIZER') && (
        <form className="card" onSubmit={create} noValidate aria-label="Create team">
          <h2>Create team</h2>
          {error && <div className="alert alert-error" role="alert">{error}</div>}
          <label htmlFor="team-name">Team name</label>
          <input id="team-name" value={name} onChange={(e) => setName(e.target.value)} />
          <button className="btn" type="submit">Create team</button>
        </form>
      )}
    </div>
  );
}

function TeamDetailCard({ id, canAdmin, myId }: { id: string; canAdmin: boolean; myId?: string }) {
  const team = useLoad<TeamDetail>(`/api/v1/teams/${id}`);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function addMember(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const order = data.get('order');
    setError(null);
    setSaving(true);
    try {
      await apiFetch(`/api/v1/teams/${id}/members/by-email`, {
        method: 'POST',
        body: { email: data.get('email'), teamRole: data.get('role'), escalationOrder: order ? Number(order) : undefined },
      });
      form.reset();
      team.reload();
    } catch (err) {
      setError(messageOf(err));
    } finally {
      setSaving(false);
    }
  }
  async function toggle(userId: string, available: boolean) {
    setError(null);
    try {
      await apiFetch(`/api/v1/teams/${id}/members/${userId}/availability`, { method: 'PATCH', body: { available } });
    } catch (err) {
      setError(messageOf(err));
    }
    team.reload();
  }
  return (
    <section className="card">
      <LoadState load={team} what="team">
        {(t) => (
          <>
            <h2>{t.name} — escalation chain</h2>
            {error && <div className="alert alert-error" role="alert">{error}</div>}
            <table>
              <thead><tr><th>Order</th><th>Name</th><th>Team role</th><th>Available</th></tr></thead>
              <tbody>
                {t.members.map((m) => (
                  <tr key={m.userId}>
                    <td>{m.escalationOrder ?? '—'}</td><td>{m.name}</td><td>{m.teamRole}</td>
                    <td>
                      {m.available ? 'Yes' : 'No'}{' '}
                      {(canAdmin || m.userId === myId) && (
                        <button type="button" className="btn btn-secondary" onClick={() => void toggle(m.userId, !m.available)}>
                          {m.available ? 'Go off-shift' : 'Go on-shift'}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {t.canManage && (
              <form onSubmit={addMember} aria-label="Add or update team member">
                <h3>Add or update team member</h3>
                <label htmlFor="team-member-email">Registered email</label>
                <input id="team-member-email" name="email" type="email" required />
                <label htmlFor="team-member-role">Team role</label>
                <select id="team-member-role" name="role" defaultValue="RESPONDER">
                  <option value="RESPONDER">Responder</option>
                  <option value="LEAD">Team lead</option>
                  <option value="MEMBER">Member</option>
                </select>
                <label htmlFor="team-member-order">Escalation position (optional)</label>
                <input id="team-member-order" name="order" type="number" min={1} step={1} />
                <button type="submit" className="btn" disabled={saving}>Save team member</button>
              </form>
            )}
          </>
        )}
      </LoadState>
    </section>
  );
}
