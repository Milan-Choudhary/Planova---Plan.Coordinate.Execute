import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { apiFetch } from '../api/client';
import { messageOf, useLoad } from '../api/useLoad';
import type { Incident, IncidentAction, IncidentHistory, PlanMember } from '../api/types';
import { fmt, LoadState, SeverityBadge, StatusBadge } from '../components/LoadState';

const LABEL: Record<IncidentAction, string> = {
  ACKNOWLEDGE: 'Acknowledge', REJECT: 'Reject (pass on)', START: 'Start work', RESOLVE: 'Resolve',
  ASSIGN: 'Assign', CLOSE: 'Close', REOPEN: 'Reopen',
};

export function IncidentDetailPage() {
  const { incidentId = '' } = useParams();
  const incident = useLoad<Incident>(`/api/v1/incidents/${incidentId}`);
  const history = useLoad<IncidentHistory>(`/api/v1/incidents/${incidentId}/history`);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [assignee, setAssignee] = useState('');
  const [busy, setBusy] = useState(false);

  const planId = incident.data?.planId;
  const needsMembers = incident.data?.allowedActions.includes('ASSIGN') ?? false;
  const members = useLoad<PlanMember[]>(needsMembers && planId ? `/api/v1/plans/${planId}/members` : null);

  async function act(action: IncidentAction, i: Incident) {
    setError(null);
    setBusy(true);
    try {
      if (action === 'ASSIGN') {
        if (!assignee) throw new Error('pick');
        await apiFetch(`/api/v1/incidents/${i.id}/assign`, { method: 'POST', body: { assigneeId: assignee } });
      } else {
        await apiFetch(`/api/v1/incidents/${i.id}/${action.toLowerCase()}`, {
          method: 'POST',
          body: { expectedVersion: i.version, note: note || undefined },
        });
      }
      setNote('');
    } catch (err) {
      setError(err instanceof Error && err.message === 'pick' ? 'Choose who to assign it to.' : messageOf(err));
    } finally {
      // Always re-read: the server's state (and allowed buttons) is the truth, e.g. after a 409.
      incident.reload();
      history.reload();
      setBusy(false);
    }
  }


  return (
    <div className="stack incident-review">
      <LoadState load={incident} what="incident">
        {(i) => (
          <>
            <nav className="breadcrumbs" aria-label="Breadcrumb">
              <Link to="/plans">Plans</Link><span aria-hidden="true">›</span>
              <Link to={'/plans/' + i.planId}>{i.planTitle}</Link><span aria-hidden="true">›</span>
              <Link to="/incidents">Incidents</Link><span aria-hidden="true">›</span>
              <span aria-current="page">Incident review</span>
            </nav>

            <section className="card incident-summary" aria-labelledby="incident-title">
              <div className="incident-identity">
                <span className="incident-emblem" aria-hidden="true">!</span>
                <div>
                  <div className="incident-title-row">
                    <h1 id="incident-title">{i.title}</h1>
                    <StatusBadge value={i.status} />
                  </div>
                  <p className="muted">{i.planTitle}</p>
                </div>
              </div>
              <dl className="incident-facts">
                <div><dt>Severity</dt><dd><SeverityBadge value={i.severity} /></dd></div>
                <div><dt>Assignee</dt><dd>{i.assigneeName ?? 'Unassigned'}</dd></div>
                <div><dt>Reported</dt><dd>{fmt(i.createdAt)}</dd></div>
                {i.status === 'ASSIGNED' && (
                  <div><dt>Acknowledge by</dt><dd className={i.ackOverdue ? 'overdue' : undefined}>
                    {fmt(i.ackDeadline)}{i.ackOverdue && ' · Overdue'}
                  </dd></div>
                )}
              </dl>
            </section>

            <nav className="review-sections" aria-label="Incident sections">
              <a href="#incident-overview">Overview</a>
              <a href="#incident-audit">Audit trail</a>
              <a href="#incident-assignments">Assignments</a>
            </nav>

            <div className="review-grid">
              <div className="stack">
                <section id="incident-overview" className="card">
                  <h2>Overview</h2>
                  <p className="review-description">{i.description}</p>
                  <p className="muted">
                    Reported by {i.reporterName}
                    {i.escalationLevel > 0 && (' · Escalated ' + i.escalationLevel + ' time(s)')}
                  </p>
                  {i.status === 'OPEN' && <p className="alert alert-error">Unassigned. An administrator is notified when no responder is available.</p>}
                  {i.resolutionNote && <p><strong>Resolution</strong><br />{i.resolutionNote}</p>}
                </section>

                <section id="incident-audit" className="card">
                  <h2>Audit trail</h2>
                  <LoadState load={history} what="audit trail">
                    {(h) => h.audit.length === 0 ? <p className="muted">No activity recorded.</p> : (
                      <ol className="audit-timeline">
                        {h.audit.map((entry) => (
                          <li key={entry.id}>
                            <time dateTime={entry.createdAt}>{fmt(entry.createdAt)}</time>
                            <div><strong>{auditLabel(entry.action)}</strong><span>{entry.actorName ?? 'System'}</span></div>
                          </li>
                        ))}
                      </ol>
                    )}
                  </LoadState>
                </section>
              </div>

              <div className="stack">
                <section id="incident-assignments" className="card">
                  <h2>Assignment history</h2>
                  <LoadState load={history} what="assignments">
                    {(h) => h.assignments.length === 0 ? <p className="muted">Never assigned.</p> : (
                      <div className="table-scroll">
                        <table>
                          <thead><tr><th>Responder</th><th>Reason</th><th>Assigned</th><th>Ended</th><th>Status</th></tr></thead>
                          <tbody>
                            {h.assignments.map((a) => (
                              <tr key={a.sequence}>
                                <td>{a.assigneeName}</td><td>{a.reason}</td>
                                <td>{fmt(a.assignedAt)}</td><td>{fmt(a.endedAt)}</td>
                                <td><StatusBadge value={a.status} /></td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                  </LoadState>
                </section>

                {error && <div className="alert alert-error" role="alert">{error}</div>}
                {i.allowedActions.length > 0 && (
                  <section className="card incident-actions">
                    <h2>Incident actions</h2>
                    <label htmlFor="inc-note">{i.allowedActions.includes('CLOSE') ? 'Close note (optional)' : 'Note (optional)'}</label>
                    <textarea id="inc-note" value={note} onChange={(e) => setNote(e.target.value)} />
                    {i.allowedActions.includes('ASSIGN') && (
                      <>
                        <label htmlFor="inc-assignee">Assign to</label>
                        <select id="inc-assignee" value={assignee} onChange={(e) => setAssignee(e.target.value)}>
                          <option value="">Choose…</option>
                          {members.data?.filter((m) => m.userRole !== 'VIEWER').map((m) => <option key={m.userId} value={m.userId}>{m.name}</option>)}
                        </select>
                      </>
                    )}
                    <div className="row incident-action-buttons">
                      {i.allowedActions.map((a) => (
                        <button key={a} type="button" className={'btn' + (a === 'REJECT' ? ' btn-danger' : '')} disabled={busy} onClick={() => void act(a, i)}>
                          {LABEL[a]}
                        </button>
                      ))}
                    </div>
                  </section>
                )}
              </div>
            </div>
          </>
        )}
      </LoadState>
    </div>
  );
}

function auditLabel(action: string) {
  const words = action.toLowerCase().replaceAll('_', ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

