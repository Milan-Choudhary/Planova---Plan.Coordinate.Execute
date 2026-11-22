import { useLoad } from '../api/useLoad';
import type { AuditEntry, PageResponse } from '../api/types';
import { AuditTable } from './PlanDetailPage';

export function AdminAuditPage() {
  const load = useLoad<PageResponse<AuditEntry>>('/api/v1/admin/audit?size=100');
  return (
    <section className="card">
      <h1>Audit log (all activity)</h1>
      <AuditTable load={load} />
    </section>
  );
}
