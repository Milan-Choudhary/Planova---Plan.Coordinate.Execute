import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useLoad } from '../api/useLoad';
import type { DashboardSummary, Incident, PageResponse, Severity } from '../api/types';
import { IncidentQueue } from '../components/IncidentQueue';
import { LoadState } from '../components/LoadState';

function Stat({ label, value, to, warn }: { label: string; value: number; to?: string; warn?: boolean }) {
  const body = (
    <>
      <span className="muted">{label}</span>
      <strong>{value}</strong>
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

const SEVERITIES: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

export function DashboardPage() {
  const { user } = useAuth();
  const load = useLoad<DashboardSummary>('/api/v1/dashboard');
  const incidents = useLoad<PageResponse<Incident>>('/api/v1/incidents?unresolvedOnly=true&size=50');
  return (
    <div className="stack">
      <header className="dashboard-heading">
        <div><h1>Operations dashboard</h1><p className="muted">Welcome, {user?.name}</p></div>
        <Link className="btn" to="/plans">View plans</Link>
      </header>
      <LoadState load={load} what="dashboard">
        {(d) => (
          <div className="dashboard-metrics">
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
      <LoadState load={incidents} what="open incidents">
        {(page) => (
          <div className="dashboard-panels">
            <section className="card dashboard-queue">
              <div className="dashboard-heading">
                <h2>Open incident queue</h2>
                <Link to="/incidents?unresolved=1">View incidents</Link>
              </div>
              <p className="muted">Showing {page.items.length} of {page.totalItems} open incidents.</p>
              <IncidentQueue items={page.items} />
            </section>
            <section className="card severity-panel">
              <h2>By severity</h2>
              <p className="muted">Open incidents shown in the queue</p>
              {SEVERITIES.map((severity) => {
                const count = page.items.filter((incident) => incident.severity === severity).length;
                return (
                  <div className="severity-row" key={severity}>
                    <span>{severity.charAt(0) + severity.slice(1).toLowerCase()}</span>
                    <meter min={0} max={Math.max(1, page.items.length)} value={count} aria-label={severity + ' incidents'} />
                    <strong>{count}</strong>
                  </div>
                );
              })}
            </section>
          </div>
        )}
      </LoadState>
    </div>
  );
}
