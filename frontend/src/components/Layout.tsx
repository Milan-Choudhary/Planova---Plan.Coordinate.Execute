import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

export function Layout() {
  const { user, logout } = useAuth();
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <NavLink to="/" className="brand">
          <span className="brand-mark" aria-hidden="true"><i /><i /><i /></span>
          Planova AI
        </NavLink>
        <nav aria-label="Main">
          <NavLink to="/" end>
            Dashboard
          </NavLink>
          <NavLink to="/plans">Plans</NavLink>
          <NavLink to="/incidents">Incidents</NavLink>
          <NavLink to="/tasks">My tasks</NavLink>
          <NavLink to="/teams">Teams</NavLink>
          {user?.role === 'ADMIN' && <NavLink to="/admin/users">Users</NavLink>}
          {user?.role === 'ADMIN' && <NavLink to="/admin/audit">Audit</NavLink>}
        </nav>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <NavLink to="/notifications" className="topbar-notifications">Notifications</NavLink>
          <div className="topbar-user">
            <span>{user?.name} <span className="badge">{user?.role}</span></span>
            <button type="button" className="btn btn-secondary" onClick={logout}>Log out</button>
          </div>
        </header>
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
