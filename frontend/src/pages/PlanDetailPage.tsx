import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { apiFetch, newIdempotencyKey } from '../api/client';
import { messageOf, useLoad } from '../api/useLoad';
import type {
  AuditEntry, Incident, PageResponse, Plan, PlanEvent, PlanMember, Severity, Task, TaskStatus,
} from '../api/types';
import { fmt, LoadState, SeverityBadge, StatusBadge } from '../components/LoadState';

type Tab = 'overview' | 'events' | 'tasks' | 'incidents' | 'members' | 'audit';
const TABS: Tab[] = ['overview', 'events', 'tasks', 'incidents', 'members', 'audit'];

export function PlanDetailPage() {
  const { planId = '' } = useParams();
  const plan = useLoad<Plan>(`/api/v1/plans/${planId}`);
  const [tab, setTab] = useState<Tab>('overview');

  return (
    <div className="stack">
      <LoadState load={plan} what="plan">
        {(p) => (
          <>
            <section className="card">
              <h1>{p.title} <StatusBadge value={p.status} /></h1>
              <p className="muted">Owner {p.ownerName} · Team {p.teamName ?? 'none'} · Your role {p.myPlanRole ?? 'via team'}</p>
              <div className="tabs" role="tablist">
                {TABS.map((t) => (
                  <button key={t} role="tab" aria-selected={tab === t} onClick={() => setTab(t)}>{t}</button>
                ))}
              </div>
              {tab === 'overview' && <Overview plan={p} reload={plan.reload} />}
              {tab === 'events' && <Events plan={p} />}
              {tab === 'tasks' && <Tasks plan={p} />}
              {tab === 'incidents' && <PlanIncidents plan={p} />}
              {tab === 'members' && <Members plan={p} />}
              {tab === 'audit' && <PlanAudit plan={p} />}
            </section>
          </>
        )}
      </LoadState>
    </div>
  );
}

function Overview({ plan, reload }: { plan: Plan; reload: () => void }) {
  const [error, setError] = useState<string | null>(null);
  async function setStatus(status: string) {
    setError(null);
    try {
      await apiFetch(`/api/v1/plans/${plan.id}/status`, { method: 'POST', body: { status } });
      reload();
    } catch (err) {
      setError(messageOf(err));
    }
  }
  return (
    <div>
      {error && <div className="alert alert-error" role="alert">{error}</div>}
      <p>{plan.description || <span className="muted">No description.</span>}</p>
      <p>
        {plan.eventCount} events · {plan.taskCount} tasks ({plan.openTaskCount} open) · {plan.openIncidentCount} open incidents
      </p>
      {plan.canManage && (
        <div className="row">
          {plan.status === 'DRAFT' && <button className="btn" onClick={() => void setStatus('ACTIVE')}>Activate</button>}
          {plan.status === 'ACTIVE' && <button className="btn btn-secondary" onClick={() => void setStatus('ARCHIVED')}>Archive</button>}
          {plan.status === 'ARCHIVED' && <button className="btn" onClick={() => void setStatus('ACTIVE')}>Restore</button>}
        </div>
      )}
    </div>
  );
}

function Events({ plan }: { plan: Plan }) {
  const events = useLoad<PlanEvent[]>(`/api/v1/plans/${plan.id}/events`);
  const [title, setTitle] = useState('');
  const [startAt, setStartAt] = useState('');
  const [endAt, setEndAt] = useState('');
  const [error, setError] = useState<string | null>(null);

  async function add(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!title.trim() || !startAt || !endAt) return setError('Title, start and end are required.');
    try {
      await apiFetch(`/api/v1/plans/${plan.id}/events`, {
        method: 'POST',
        body: { title, startAt: new Date(startAt).toISOString(), endAt: new Date(endAt).toISOString() },
      });
      setTitle(''); setStartAt(''); setEndAt('');
      events.reload();
    } catch (err) {
      setError(messageOf(err));
    }
  }
  return (
    <div>
      <LoadState load={events} what="events">
        {(list) => list.length === 0 ? <p className="muted">No events yet.</p> : (
          <table>
            <thead><tr><th>Event</th><th>Starts</th><th>Ends</th></tr></thead>
            <tbody>{list.map((ev) => <tr key={ev.id}><td>{ev.title}</td><td>{fmt(ev.startAt)}</td><td>{fmt(ev.endAt)}</td></tr>)}</tbody>
          </table>
        )}
      </LoadState>
      {plan.canEdit && (
        <form onSubmit={add} noValidate aria-label="Add event">
          <h2>Add event</h2>
          {error && <div className="alert alert-error" role="alert">{error}</div>}
          <label htmlFor="ev-title">Event title</label>
          <input id="ev-title" value={title} onChange={(e) => setTitle(e.target.value)} />
          <label htmlFor="ev-start">Starts</label>
          <input id="ev-start" type="datetime-local" value={startAt} onChange={(e) => setStartAt(e.target.value)} />
          <label htmlFor="ev-end">Ends</label>
          <input id="ev-end" type="datetime-local" value={endAt} onChange={(e) => setEndAt(e.target.value)} />
          <button className="btn" type="submit">Add event</button>
        </form>
      )}
    </div>
  );
}

const NEXT: Record<TaskStatus, TaskStatus[]> = {
  TODO: ['IN_PROGRESS', 'CANCELLED'],
  IN_PROGRESS: ['TODO', 'DONE', 'CANCELLED'],
  DONE: ['IN_PROGRESS'],
  CANCELLED: ['TODO'],
};

function Tasks({ plan }: { plan: Plan }) {
  const tasks = useLoad<PageResponse<Task>>(`/api/v1/plans/${plan.id}/tasks?size=100`);
  const members = useLoad<PlanMember[]>(plan.canEdit ? `/api/v1/plans/${plan.id}/workers` : null);
  const [title, setTitle] = useState('');
  const [assigneeId, setAssigneeId] = useState('');
  const [error, setError] = useState<string | null>(null);

  async function add(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!title.trim()) return setError('Title is required.');
    try {
      await apiFetch(`/api/v1/plans/${plan.id}/tasks`, { method: 'POST', body: { title, assigneeId: assigneeId || undefined } });
      setTitle(''); setAssigneeId('');
      tasks.reload();
    } catch (err) {
      setError(messageOf(err));
    }
  }
  async function move(t: Task, status: TaskStatus) {
    setError(null);
    try {
      await apiFetch(`/api/v1/tasks/${t.id}`, { method: 'PATCH', body: { status, expectedVersion: t.version } });
    } catch (err) {
      setError(messageOf(err));
    }
    tasks.reload();
  }
  return (
    <div>
      {error && <div className="alert alert-error" role="alert">{error}</div>}
      <LoadState load={tasks} what="tasks">
        {(page) => page.items.length === 0 ? <p className="muted">No tasks yet.</p> : (
          <table>
            <thead><tr><th>Task</th><th>Assignee</th><th>Deadline</th><th>Status</th><th /></tr></thead>
            <tbody>
              {page.items.map((t) => (
                <tr key={t.id}>
                  <td>{t.title}</td>
                  <td>{t.assigneeName ?? '—'}</td>
                  <td className={t.overdue ? 'overdue' : undefined}>{fmt(t.deadline)}{t.overdue ? ' (overdue)' : ''}</td>
                  <td><StatusBadge value={t.status} /></td>
                  <td>
                    {NEXT[t.status].map((s) => (
                      <button key={s} type="button" className="btn btn-secondary" onClick={() => void move(t, s)}>
                        → {s.replace('_', ' ')}
                      </button>
                    ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </LoadState>
      {plan.canEdit && (
        <form onSubmit={add} noValidate aria-label="Add task">
          <h2>Add task</h2>
          <label htmlFor="task-title">Task title</label>
          <input id="task-title" value={title} onChange={(e) => setTitle(e.target.value)} />
          <label htmlFor="task-assignee">Assign to</label>
          <select id="task-assignee" value={assigneeId} onChange={(e) => setAssigneeId(e.target.value)}>
            <option value="">Unassigned</option>
            {members.data?.filter((m) => m.userRole !== 'VIEWER').map((m) => <option key={m.userId} value={m.userId}>{m.name}</option>)}
          </select>
          <button className="btn" type="submit">Add task</button>
        </form>
      )}
    </div>
  );
}

function PlanIncidents({ plan }: { plan: Plan }) {
  const incidents = useLoad<PageResponse<Incident>>(`/api/v1/incidents?planId=${plan.id}&size=50`);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [severity, setSeverity] = useState<Severity | ''>('');
  const [error, setError] = useState<string | null>(null);
  // One key per form submission attempt: if the network drops and the user clicks again,
  // the server recognises the retry and does not create a second incident.
  const [key, setKey] = useState(() => newIdempotencyKey());

  async function report(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!title.trim() || !description.trim()) return setError('Title and description are required.');
    try {
      await apiFetch(`/api/v1/plans/${plan.id}/incidents`, {
        method: 'POST',
        headers: { 'Idempotency-Key': key },
        body: { title, description, severity: severity || undefined },
      });
      setTitle(''); setDescription(''); setSeverity('');
      setKey(newIdempotencyKey());
      incidents.reload();
    } catch (err) {
      setError(messageOf(err));
    }
  }
  return (
    <div>
      <LoadState load={incidents} what="incidents">
        {(page) => page.items.length === 0 ? <p className="muted">No incidents reported.</p> : <IncidentTable items={page.items} />}
      </LoadState>
      {plan.myPlanRole !== 'VIEWER' && plan.status !== 'ARCHIVED' && (
        <form onSubmit={report} noValidate aria-label="Report incident">
          <h2>Report an incident</h2>
          {error && <div className="alert alert-error" role="alert">{error}</div>}
          <label htmlFor="inc-title">What happened (short)</label>
          <input id="inc-title" value={title} onChange={(e) => setTitle(e.target.value)} />
          <label htmlFor="inc-desc">Details</label>
          <textarea id="inc-desc" value={description} onChange={(e) => setDescription(e.target.value)} />
          <label htmlFor="inc-sev">Severity (the system may raise it)</label>
          <select id="inc-sev" value={severity} onChange={(e) => setSeverity(e.target.value as Severity | '')}>
            <option value="">Let the system decide</option>
            {(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as Severity[]).map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
          <button className="btn" type="submit">Report incident</button>
        </form>
      )}
    </div>
  );
}

export function IncidentTable({ items }: { items: Incident[] }) {
  return (
    <table>
      <thead><tr><th>Incident</th><th>Severity</th><th>Status</th><th>Assignee</th><th>Ack by</th></tr></thead>
      <tbody>
        {items.map((i) => (
          <tr key={i.id}>
            <td><Link to={`/incidents/${i.id}`}>{i.title}</Link></td>
            <td><SeverityBadge value={i.severity} /></td>
            <td><StatusBadge value={i.status} /></td>
            <td>{i.assigneeName ?? '—'}{i.escalationLevel > 0 ? ` (escalated ×${i.escalationLevel})` : ''}</td>
            <td className={i.ackOverdue ? 'overdue' : undefined}>{i.status === 'ASSIGNED' ? fmt(i.ackDeadline) : '—'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Members({ plan }: { plan: Plan }) {
  const path = `/api/v1/plans/${plan.id}/members`;
  const members = useLoad<PlanMember[]>(path);
  const canDelegate = plan.canManage && plan.status !== 'ARCHIVED';
  const workers = useLoad<PlanMember[]>(canDelegate ? `/api/v1/plans/${plan.id}/workers` : null);
  const [selected, setSelected] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState('');

  async function changeEditor(userId: string, grant: boolean) {
    setBusy(true);
    setError(null);
    setNotice('');
    try {
      await apiFetch(grant ? path : `${path}/${userId}`, {
        method: grant ? 'PUT' : 'DELETE',
        body: grant ? { userId, planRole: 'EDITOR' } : undefined,
      });
      setSelected('');
      setNotice(grant ? 'Editor access granted.' : 'Editor access revoked.');
      members.reload();
      workers.reload();
    } catch (err) {
      setError(messageOf(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="stack">
      {error && <p className="alert alert-error" role="alert">{error}</p>}
      {notice && <p className="alert alert-ok" role="status">{notice}</p>}
      {canDelegate && (
        <LoadState load={workers} what="eligible organizers">
          {(list) => {
            const candidates = list.filter((m) =>
              m.userRole === 'ORGANIZER' && m.planRole !== 'OWNER' && m.planRole !== 'EDITOR');
            return (
              <form className="card" onSubmit={(event) => {
                event.preventDefault();
                if (selected) void changeEditor(selected, true);
              }}>
                <h2>Grant plan Editor access</h2>
                <p className="muted">Editors can change the schedule and manage tasks and incidents across this plan.</p>
                {candidates.length === 0 ? <p>No eligible organizers on this plan or its responder team.</p> : (
                  <>
                    <label htmlFor="plan-editor">Organizer</label>
                    <select id="plan-editor" value={selected} onChange={(e) => setSelected(e.target.value)}>
                      <option value="">Choose an organizer</option>
                      {candidates.map((m) => <option key={m.userId} value={m.userId}>{m.name} ({m.email})</option>)}
                    </select>
                    <button type="submit" className="btn" disabled={!selected || busy}>Grant Editor access</button>
                  </>
                )}
              </form>
            );
          }}
        </LoadState>
      )}
      <LoadState load={members} what="members">
        {(list) => (
          <table>
            <thead><tr><th>Name</th><th>Email</th><th>Plan role</th>{canDelegate && <th>Access</th>}</tr></thead>
            <tbody>{list.map((m) => (
              <tr key={m.userId}>
                <td>{m.name}</td><td>{m.email}</td><td>{m.planRole}</td>
                {canDelegate && <td>{m.planRole === 'EDITOR' && (
                  <button type="button" className="btn btn-secondary" disabled={busy}
                    aria-label={`Revoke Editor access for ${m.name}`}
                    onClick={() => void changeEditor(m.userId, false)}>Revoke Editor</button>
                )}</td>}
              </tr>
            ))}</tbody>
          </table>
        )}
      </LoadState>
    </div>
  );
}

function PlanAudit({ plan }: { plan: Plan }) {
  const audit = useLoad<PageResponse<AuditEntry>>(`/api/v1/plans/${plan.id}/audit?size=50`);
  return <AuditTable load={audit} />;
}

export function AuditTable({ load }: { load: ReturnType<typeof useLoad<PageResponse<AuditEntry>>> }) {
  return (
    <LoadState load={load} what="audit history">
      {(page) => page.items.length === 0 ? <p className="muted">No history yet.</p> : (
        <table>
          <thead><tr><th>When</th><th>Who</th><th>Action</th><th>Entity</th></tr></thead>
          <tbody>
            {page.items.map((a) => (
              <tr key={a.id}><td>{fmt(a.createdAt)}</td><td>{a.actorName ?? 'system'}</td><td>{a.action}</td><td>{a.entityType}</td></tr>
            ))}
          </tbody>
        </table>
      )}
    </LoadState>
  );
}
