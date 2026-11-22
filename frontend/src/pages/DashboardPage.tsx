import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useLoad } from '../api/useLoad';
import type { DashboardSummary } from '../api/types';
import { LoadState } from '../components/LoadState';

function Stat({ label, value, to, warn }: { label: string; value: number; to?: string; warn?: boolean }) {
  const body = (
    <>
      <strong>{value}</strong>
      <span className="muted">{label}</span>
    </>
  );
  const cls = `card stat${warn && value > 0 ? ' warn' : ''}`;
  return to ? (
    <Link to={to} className={cls}>
      {body}
    </Link>
  ) : (
    <div className={cls}>{body}</div>
  );
}

/** Every number is computed by the server for THIS user's visible plans; nothing is derived in the browser. */
export function DashboardPage() {
  const { user } = useAuth();
  const load = useLoad<DashboardSummary>('/api/v1/dashboard');
  return (
    <div className="stack">
      <section className="card">
        <h1>Welcome, {user?.name}</h1>
        <p className="muted">Signed in as {user?.email} · <span className="badge">{user?.role}</span></p>
      </section>
      <LoadState load={load} what="dashboard">
        {(d) => (
          <div className="grid">
            <Stat label="Active plans" value={d.plans} to="/plans" />
            <Stat label="Open incidents" value={d.openIncidents} to="/incidents?unresolved=1" />
            <Stat label="Assigned to me" value={d.myAssignedIncidents} to="/incidents?mine=1" />
            <Stat label="Overdue acknowledgements" value={d.overdueAcknowledgements} warn to="/incidents?unresolved=1" />
            <Stat label="My open tasks" value={d.myOpenTasks} to="/tasks" />
            <Stat label="My overdue tasks" value={d.myOverdueTasks} warn to="/tasks" />
            <Stat label="Events in next 7 days" value={d.upcomingEvents} />
            <Stat label="Unread notifications" value={d.unreadNotifications} to="/notifications" />
          </div>
        )}
      </LoadState>
    </div>
  );
}
