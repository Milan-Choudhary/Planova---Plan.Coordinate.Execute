import { useState, type FormEvent } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { apiFetch } from '../api/client';
import { messageOf } from '../api/useLoad';
import { useAuth } from '../auth/AuthContext';

export function SignupPage() {
  const { status } = useAuth();
  const navigate = useNavigate();
  const [error, setError] = useState<string>();
  const [submitting, setSubmitting] = useState(false);

  if (status === 'authenticated') return <Navigate to="/" replace />;

  async function signup(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setError(undefined);
    setSubmitting(true);
    try {
      await apiFetch('/api/v1/auth/register', {
        method: 'POST',
        auth: false,
        body: { name: data.get('name'), email: data.get('email'), password: data.get('password'), role: data.get('role') },
      });
      navigate('/login', { replace: true, state: { registered: true } });
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="center-screen auth-screen">
      <form className="card login-card" onSubmit={signup} aria-labelledby="signup-title">
        <p className="auth-brand">Planova AI</p>
        <h1 id="signup-title">Create an account</h1>
        <p className="muted">Organizers create event plans. Members join a team through its organizer.</p>
        {error && <p className="alert alert-error" role="alert">{error}</p>}
        <label htmlFor="signup-name">Name</label>
        <input id="signup-name" name="name" autoComplete="name" required maxLength={120} />
        <label htmlFor="signup-email">Email</label>
        <input id="signup-email" name="email" type="email" autoComplete="username" required maxLength={254} />
        <label htmlFor="signup-role">Account type</label>
        <select id="signup-role" name="role" defaultValue="TEAM_MEMBER">
          <option value="TEAM_MEMBER">Team member</option>
          <option value="ORGANIZER">Organizer</option>
        </select>
        <label htmlFor="signup-password">Password</label>
        <input id="signup-password" name="password" type="password" autoComplete="new-password"
          required minLength={12} maxLength={72} aria-describedby="signup-password-hint" />
        <small id="signup-password-hint" className="muted">Use at least 12 characters and no more than 72 UTF-8 bytes.</small>
        <button type="submit" className="btn" disabled={submitting}>{submitting ? 'Creating…' : 'Sign up'}</button>
        <p className="auth-footer">Already have an account? <Link to="/login">Sign in</Link></p>
      </form>
    </div>
  );
}
