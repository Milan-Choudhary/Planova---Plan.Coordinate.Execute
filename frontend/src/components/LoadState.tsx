import type { ReactNode } from 'react';
import type { LoadResult } from '../api/useLoad';

interface Props<T> {
  load: LoadResult<T>;
  what: string;
  /** Called only when data is present. */
  children: (data: T) => ReactNode;
}

/** The three states every list needs, in one place: loading, error with retry, then the content. */
export function LoadState<T>({ load, what, children }: Props<T>) {
  if (load.loading && load.data === null) return <p role="status">Loading {what}…</p>;
  if (load.error && load.data === null) {
    return (
      <div className="alert alert-error" role="alert">
        {load.error.message}{' '}
        <button type="button" className="btn btn-secondary" onClick={load.reload}>
          Retry
        </button>
      </div>
    );
  }
  if (load.data === null) return null;
  return <>{children(load.data)}</>;
}

export function fmt(iso?: string): string {
  return iso ? new Date(iso).toLocaleString() : '—';
}

export function SeverityBadge({ value }: { value: string }) {
  return <span className={`badge sev-${value.toLowerCase()}`}>{value}</span>;
}

export function StatusBadge({ value }: { value: string }) {
  return <span className={`badge status-${value.toLowerCase()}`}>{value.replaceAll('_', ' ')}</span>;
}
