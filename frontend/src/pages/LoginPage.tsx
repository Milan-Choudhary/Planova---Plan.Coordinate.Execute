import { useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';

interface FormErrors {
  email?: string;
  password?: string;
}

export function LoginPage() {
  const { status, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const redirect = location.state as { from?: string; registered?: boolean } | null;
  const from = redirect?.from ?? '/';

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<FormErrors>({});
  const [serverError, setServerError] = useState<ApiError | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (status === 'authenticated') return <Navigate to={from} replace />;

  function validate(): FormErrors {
    const next: FormErrors = {};
    if (!email.trim()) next.email = 'Enter your email address';
    if (!password) next.password = 'Enter your password';
    return next;
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setServerError(null);
    const found = validate();
    setErrors(found);
    if (Object.keys(found).length > 0) return;

    setSubmitting(true);
    try {
      await login(email.trim(), password);
      navigate(from, { replace: true });
    } catch (err) {
      setServerError(err instanceof ApiError ? err : new ApiError(0, 'UNKNOWN', 'Something went wrong.'));
      setPassword(''); // never keep a rejected password in the field
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="center-screen auth-screen">
      <form className="card login-card" onSubmit={handleSubmit} noValidate aria-labelledby="login-title">
        <p className="auth-brand">Planova AI</p>
        <h1 id="login-title">Sign in to Planova AI</h1>
        {redirect?.registered && <p className="alert alert-ok" role="status">Account created. Sign in to continue.</p>}

        {serverError && (
          <div className="alert alert-error" role="alert">
            {serverError.message}
            {serverError.status >= 500 && serverError.correlationId && (
              <small> (reference: {serverError.correlationId})</small>
            )}
          </div>
        )}

        <label htmlFor="email">Email</label>
        <input
          id="email"
          type="email"
          autoComplete="username"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          aria-invalid={errors.email ? true : undefined}
          aria-describedby={errors.email ? 'email-error' : undefined}
        />
        {errors.email && (
          <p id="email-error" className="field-error">
            {errors.email}
          </p>
        )}

        <label htmlFor="password">Password</label>
        <input
          id="password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          aria-invalid={errors.password ? true : undefined}
          aria-describedby={errors.password ? 'password-error' : undefined}
        />
        {errors.password && (
          <p id="password-error" className="field-error">
            {errors.password}
          </p>
        )}

        <button type="submit" className="btn" disabled={submitting}>
          {submitting ? 'Signing in…' : 'Sign in'}
        </button>
        <p className="auth-footer">New to Planova? <Link to="/signup">Create an account</Link></p>
      </form>
    </div>
  );
}
