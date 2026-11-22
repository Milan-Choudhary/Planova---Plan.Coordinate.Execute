import { useSearchParams } from 'react-router-dom';
import { useLoad } from '../api/useLoad';
import type { Incident, PageResponse } from '../api/types';
import { LoadState } from '../components/LoadState';
import { IncidentTable } from './PlanDetailPage';

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
        {(page) => page.items.length === 0 ? <p className="muted">No incidents to show.</p> : <IncidentTable items={page.items} />}
      </LoadState>
    </section>
  );
}
