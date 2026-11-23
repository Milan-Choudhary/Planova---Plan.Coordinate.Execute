import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse } from '../test/helpers';
import { useLoad } from './useLoad';

const fetchMock = vi.fn();
beforeEach(() => vi.stubGlobal('fetch', fetchMock));
afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
});

describe('useLoad', () => {
  it('ignores a slow stale response after the path changed', async () => {
    let releaseSlow: (r: Response) => void = () => undefined;
    fetchMock.mockImplementation((url: string) =>
      url.endsWith('/a') ? new Promise<Response>((res) => { releaseSlow = res; }) : Promise.resolve(jsonResponse(200, { v: 'B' })));

    const { result, rerender } = renderHook(({ p }) => useLoad<{ v: string }>(p), { initialProps: { p: '/a' } });
    rerender({ p: '/b' });
    await waitFor(() => expect(result.current.data).toEqual({ v: 'B' }));

    await act(async () => { releaseSlow(jsonResponse(200, { v: 'A' })); });
    expect(result.current.data).toEqual({ v: 'B' });
  });

  it('reload() fetches again', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(200, { n: 1 })));
    const { result } = renderHook(() => useLoad<{ n: number }>('/x'));
    await waitFor(() => expect(result.current.data).not.toBeNull());
    act(() => result.current.reload());
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
  });

  it('does not fetch when path is null', () => {
    renderHook(() => useLoad('/x'.slice(0, 0) || null));
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
