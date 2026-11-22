import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { Incident } from '../api/types';
import { fmt, SeverityBadge, StatusBadge } from './LoadState';

export function IncidentQueue({ items }: { items: Incident[] }) {
  const [search, setSearch] = useState('');
  const [severity, setSeverity] = useState('');
  const [status, setStatus] = useState('');
  const query = search.trim().toLowerCase();
  const filtered = items.filter((incident) =>
    (!severity || incident.severity === severity) &&
    (!status || incident.status === status) &&
    [incident.title, incident.planTitle, incident.id, incident.assigneeName]
      .some((value) => value?.toLowerCase().includes(query)),
  );

  return (
    <div className="incident-queue">
      <div className="queue-filters">
        <input aria-label="Search loaded incidents" placeholder="Search loaded incidents"
          type="search" value={search} onChange={(e) => setSearch(e.target.value)} />
        <select aria-label="Severity" value={severity} onChange={(e) => setSeverity(e.target.value)}>
          <option value="">All severities</option>
          {['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].map((value) => <option key={value}>{value}</option>)}
        </select>
        <select aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">All statuses</option>
          {['OPEN', 'ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'].map((value) =>
            <option key={value} value={value}>{value.replaceAll('_', ' ')}</option>)}
        </select>
      </div>
      {filtered.length === 0 ? <p className="muted">No incidents match this view.</p> : (
        <ul className="queue-list">
          {filtered.map((incident) => (
            <li key={incident.id}>
              <Link className="queue-item" to={`/incidents/${incident.id}`}>
                <div>
                  <div className="row queue-badges">
                    <SeverityBadge value={incident.severity} />
                    <StatusBadge value={incident.status} />
                    {incident.escalationLevel > 0 && <span className="badge">Escalated {incident.escalationLevel}×</span>}
                  </div>
                  <strong>{incident.title}</strong>
                  <span className="muted">{incident.planTitle} · {incident.assigneeName ?? 'Unassigned'}</span>
                </div>
                {incident.status === 'ASSIGNED' && incident.ackDeadline && (
                  <span className={incident.ackOverdue ? 'overdue' : 'muted'}>
                    {incident.ackOverdue ? 'Overdue' : 'Acknowledge by'}<br />{fmt(incident.ackDeadline)}
                  </span>
                )}
                <span className="queue-arrow" aria-hidden="true">›</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
