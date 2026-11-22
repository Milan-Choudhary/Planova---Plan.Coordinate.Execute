import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import type { Role } from '../api/types';
import { useAuth } from './AuthContext';

interface Props {
  children: ReactNode;
  /** If given, only these roles see the page. UI convenience ONLY: the backend enforces it for real. */
  roles?: readonly Role[];
}

export function ProtectedRoute({ children, roles }: Props) {
  const { status, user } = useAuth();
  const location = useLocation();

  if (status === 'loading') {
    return (
      <div className="center-screen" role="status" aria-live="polite">
        Restoring your session…
      </div>
    );
  }
  if (status === 'anonymous' || !user) {
    // Remember where the user was heading so login can send them back.
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  if (roles && !roles.includes(user.role)) {
    return (
      <div className="card" role="alert">
        <h2>Not authorized</h2>
        <p>Your role ({user.role}) does not allow access to this page.</p>
      </div>
    );
  }
  return <>{children}</>;
}
