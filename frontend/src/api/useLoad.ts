import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, apiFetch } from './client';

export interface LoadResult<T> {
  data: T | null;
  loading: boolean;
  error: ApiError | null;
  reload: () => void;
}

/**
 * GETs `path` and exposes loading / error / data. Re-fetches when `path` changes or reload() is called.
 * A stale response (path changed while in flight, or the component unmounted) is ignored, so a slow
 * old request can never overwrite newer data.
 */
export function useLoad<T>(path: string | null): LoadResult<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState<boolean>(path !== null);
  const [error, setError] = useState<ApiError | null>(null);
  const [tick, setTick] = useState(0);
  const latest = useRef(0);

  useEffect(() => {
    if (path === null) return;
    const ticket = ++latest.current;
    setLoading(true);
    setError(null);
    apiFetch<T>(path)
      .then((result) => {
        if (ticket !== latest.current) return;
        setData(result);
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (ticket !== latest.current) return;
        setError(err instanceof ApiError ? err : new ApiError(0, 'UNKNOWN', 'Something went wrong.'));
        setLoading(false);
      });
    return () => {
      latest.current++;
    };
  }, [path, tick]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { data, loading, error, reload };
}

/** Message for any thrown value, for action handlers. */
export function messageOf(err: unknown): string {
  return err instanceof ApiError ? err.message : 'Something went wrong.';
}
