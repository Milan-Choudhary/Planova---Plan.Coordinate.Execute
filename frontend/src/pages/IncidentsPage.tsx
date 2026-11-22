import { useSearchParams } from 'react-router-dom';
import { useLoad } from '../api/useLoad';
import type { Incident, PageResponse } from '../api/types';
import { LoadState } from '../components/LoadState';
import { IncidentQueue } from '../components/IncidentQueue';

export function IncidentsPage() {
  const [params] = useSearchParams();
  const query = new URLSearchParams({ size: '50' });
  if (params.get('mine')) query.set('assignedToMe', 'true');
  if (params.get('unresolved')) query.set('unresolvedOnly', 'true');
  const load = useLoad<PageResponse<Incident>>(`/api/v1/incidents?${query.toString()}`);
  return (
    <section className="card">
      <h1>{params.get('mine') ? 'Incidents assigned to me' : 'Incidents'}</h1>
      <LoadState load={load} what="incidents">
        {(page) => (
          <>
            <p className="muted">Showing {page.items.length} of {page.totalItems} incidents, most recently updated first.</p>
            <IncidentQueue items={page.items} />
          </>
        )}
      </LoadState>
    </section>
  );
}
