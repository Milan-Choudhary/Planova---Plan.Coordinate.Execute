import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { ApiError, apiFetch } from '../api/client';
import { ROLES, type CreateUserRequest, type PageResponse, type Role, type User, type UserStatus } from '../api/types';

type LoadState = 'loading' | 'ready' | 'error';

const EMPTY_FORM: CreateUserRequest = { email: '', name: '', role: 'TEAM_MEMBER', password: '' };

export function UsersAdminPage() {
  const [users, setUsers] = useState<User[]>([]);
  const [loadState, setLoadState] = useState<LoadState>('loading');
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [form, setForm] = useState<CreateUserRequest>(EMPTY_FORM);
  const [formError, setFormError] = useState<ApiError | null>(null);
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [rowError, setRowError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoadState('loading');
    try {
      const page = await apiFetch<PageResponse<User>>('/api/v1/admin/users?page=0&size=50');
      setUsers(page.items);
      setLoadState('ready');
    } catch (err) {
      setLoadError(err instanceof ApiError ? err : null);
      setLoadState('error');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function handleCreate(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    setNotice(null);
    setSaving(true);
    try {
      const created = await apiFetch<User>('/api/v1/admin/users', { method: 'POST', body: form });
      setForm(EMPTY_FORM);
      setNotice(`Created ${created.email}`);
      await load();
    } catch (err) {
      setFormError(err instanceof ApiError ? err : new ApiError(0, 'UNKNOWN', 'Something went wrong.'));
    } finally {
      setSaving(false);
    }
  }

  async function handleChange(user: User, change: { role?: Role; status?: UserStatus }) {
    setRowError(null);
    setNotice(null);
    try {
      const updated = await apiFetch<User>(`/api/v1/admin/users/${user.id}`, { method: 'PATCH', body: change });
      setUsers((prev) => prev.map((u) => (u.id === updated.id ? updated : u)));
      setNotice(`Updated ${updated.email}`);
    } catch (err) {
      setRowError(err instanceof ApiError ? err.message : 'Update failed');
      await load(); // re-sync: the server state is the truth, not our optimistic guess
    }
  }

  const fieldError = (field: string) => formError?.fieldErrors.find((f) => f.field === field)?.message;

  return (
    <div className="stack">
      <section className="card">
        <h1>Users</h1>

        {notice && (
          <div className="alert alert-ok" role="status">
            {notice}
          </div>
        )}
        {rowError && (
          <div className="alert alert-error" role="alert">
            {rowError}
          </div>
        )}

        {loadState === 'loading' && <p role="status">Loading users…</p>}

        {loadState === 'error' && (
          <div className="alert alert-error" role="alert">
            {loadError?.message ?? 'Could not load users.'}{' '}
            <button type="button" className="btn btn-secondary" onClick={() => void load()}>
              Retry
            </button>
          </div>
        )}

        {loadState === 'ready' && users.length === 0 && <p className="muted">No users yet.</p>}

        {loadState === 'ready' && users.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Email</th>
                <th>Role</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {users.map((u) => (
                <tr key={u.id}>
                  <td>{u.name}</td>
                  <td>{u.email}</td>
                  <td>
                    <select
                      aria-label={`Role for ${u.email}`}
                      value={u.role}
                      onChange={(e) => void handleChange(u, { role: e.target.value as Role })}
                    >
                      {ROLES.map((r) => (
                        <option key={r} value={r}>
                          {r}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td>
                    <select
                      aria-label={`Status for ${u.email}`}
                      value={u.status}
                      onChange={(e) => void handleChange(u, { status: e.target.value as UserStatus })}
                    >
                      <option value="ACTIVE">ACTIVE</option>
                      <option value="DISABLED">DISABLED</option>
                    </select>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <form className="card" onSubmit={handleCreate} noValidate aria-labelledby="create-user-title">
        <h2 id="create-user-title">Create user</h2>

        {formError && (
          <div className="alert alert-error" role="alert">
            {formError.message}
          </div>
        )}

        <label htmlFor="new-email">Email</label>
        <input id="new-email" type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
        {fieldError('email') && <p className="field-error">{fieldError('email')}</p>}

        <label htmlFor="new-name">Name</label>
        <input id="new-name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
        {fieldError('name') && <p className="field-error">{fieldError('name')}</p>}

        <label htmlFor="new-role">Role</label>
        <select id="new-role" value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value as Role })}>
          {ROLES.map((r) => (
            <option key={r} value={r}>
              {r}
            </option>
          ))}
        </select>

        <label htmlFor="new-password">Initial password (12+ characters)</label>
        <input
          id="new-password"
          type="password"
          autoComplete="new-password"
          value={form.password}
          onChange={(e) => setForm({ ...form, password: e.target.value })}
        />
        {fieldError('password') && <p className="field-error">{fieldError('password')}</p>}

        <button type="submit" className="btn" disabled={saving}>
          {saving ? 'Creating…' : 'Create user'}
        </button>
      </form>
    </div>
  );
}
