import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { apiFetch, ApiError } from '../api/client';
import { useLoad } from '../api/useLoad';
import { useAuth } from '../auth/AuthContext';
import type { PageResponse, Plan, Team } from '../api/types';
import { LoadState, StatusBadge } from '../components/LoadState';

export function PlansPage() {
  const { user } = useAuth();
  const plans = useLoad<PageResponse<Plan>>('/api/v1/plans?size=50');
  const canCreate = user?.role === 'ADMIN' || user?.role === 'ORGANIZER';
  return (
    <div className="stack">
      <section className="card">
        <h1>Plans</h1>
        <LoadState load={plans} what="plans">
          {(page) =>
            page.items.length === 0 ? (
              <p className="muted">No plans yet.{canCreate ? ' Create the first one below.' : ''}</p>
            ) : (
              <table>
                <thead>
                  <tr><th>Title</th><th>Status</th><th>Team</th><th>Open tasks</th><th>Open incidents</th></tr>
                </thead>
                <tbody>
                  {page.items.map((p) => (
                    <tr key={p.id}>
                      <td><Link to={`/plans/${p.id}`}>{p.title}</Link></td>
                      <td><StatusBadge value={p.status} /></td>
                      <td>{p.teamName ?? '—'}</td>
                      <td>{p.openTaskCount}</td>
                      <td>{p.openIncidentCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )
          }
        </LoadState>
      </section>
      {canCreate && <CreatePlanForm />}
    </div>
  );
}

function CreatePlanForm() {
  const navigate = useNavigate();
  const teams = useLoad<Team[]>('/api/v1/teams');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [teamId, setTeamId] = useState('');
  const [error, setError] = useState<ApiError | null>(null);
  const [saving, setSaving] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!title.trim()) {
      setError(new ApiError(400, 'VALIDATION_FAILED', 'Title is required', [{ field: 'title', message: 'must not be blank' }]));
      return;
    }
    setSaving(true);
    try {
      const created = await apiFetch<Plan>('/api/v1/plans', {
        method: 'POST',
        body: { title, description: description || undefined, teamId: teamId || undefined },
      });
      navigate(`/plans/${created.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError(0, 'UNKNOWN', 'Something went wrong.'));
      setSaving(false);
    }
  }
  const fieldError = error?.fieldErrors.find((f) => f.field === 'title')?.message;

  return (
    <form className="card" onSubmit={submit} noValidate aria-labelledby="new-plan">
      <h2 id="new-plan">Create plan</h2>
      {error && <div className="alert alert-error" role="alert">{error.message}</div>}
      <label htmlFor="plan-title">Title</label>
      <input id="plan-title" value={title} onChange={(e) => setTitle(e.target.value)} aria-invalid={!!fieldError} />
      {fieldError && <p className="field-error">{fieldError}</p>}
      <label htmlFor="plan-desc">Description</label>
      <textarea id="plan-desc" value={description} onChange={(e) => setDescription(e.target.value)} />
      <label htmlFor="plan-team">Responder team (handles incidents)</label>
      <select id="plan-team" value={teamId} onChange={(e) => setTeamId(e.target.value)}>
        <option value="">— none (incidents cannot be auto-assigned) —</option>
        {teams.data?.filter((t) => t.canManage).map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
      </select>
      <button className="btn" type="submit" disabled={saving}>{saving ? 'Creating…' : 'Create plan'}</button>
    </form>
  );
}
