import { Link } from 'react-router-dom';
import { useLoad } from '../api/useLoad';
import type { PageResponse, Task } from '../api/types';
import { fmt, LoadState, StatusBadge } from '../components/LoadState';

export function MyTasksPage() {
  const load = useLoad<PageResponse<Task>>('/api/v1/tasks/mine?size=100');
  return (
    <section className="card">
      <h1>My open tasks</h1>
      <LoadState load={load} what="tasks">
        {(page) => page.items.length === 0 ? <p className="muted">No open tasks assigned to you.</p> : (
          <table>
            <thead><tr><th>Task</th><th>Plan</th><th>Deadline</th><th>Status</th></tr></thead>
            <tbody>
              {page.items.map((t) => (
                <tr key={t.id}>
                  <td>{t.title}</td>
                  <td><Link to={`/plans/${t.planId}`}>{t.planTitle}</Link></td>
                  <td className={t.overdue ? 'overdue' : undefined}>{fmt(t.deadline)}{t.overdue ? ' (overdue)' : ''}</td>
                  <td><StatusBadge value={t.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </LoadState>
    </section>
  );
}
