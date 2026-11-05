import type { ReactNode } from 'react';
import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useRefreshWhilePending } from './useRefreshWhilePending';

let client: QueryClient;
let spy: ReturnType<typeof vi.spyOn>;

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  vi.useFakeTimers();
  client = new QueryClient();
  spy = vi.spyOn(client, 'invalidateQueries').mockResolvedValue(undefined);
});
afterEach(() => {
  vi.useRealTimers();
});

type Props = { status: 'ready' | 'pending' | 'none' | undefined; key: string[] };
const initial: Props = { status: 'pending', key: ['recipe', 'a'] };

function setup(props: Props = initial) {
  return renderHook((p: Props) => useRefreshWhilePending(p.status, p.key), { wrapper, initialProps: props });
}

test('refreshes every 10 seconds while pending, 12 times, then stops waiting', () => {
  const { result } = setup();
  expect(result.current).toBe(true);
  act(() => vi.advanceTimersByTime(10_000));
  expect(spy).toHaveBeenCalledTimes(1);
  expect(spy).toHaveBeenCalledWith({ queryKey: ['recipe', 'a'] });
  act(() => vi.advanceTimersByTime(110_000));
  expect(spy).toHaveBeenCalledTimes(12);
  expect(result.current).toBe(false);
  act(() => vi.advanceTimersByTime(60_000));
  expect(spy).toHaveBeenCalledTimes(12);
});

test('stops when the status is no longer pending', () => {
  const { result, rerender } = setup();
  act(() => vi.advanceTimersByTime(20_000));
  expect(spy).toHaveBeenCalledTimes(2);
  rerender({ status: 'ready', key: ['recipe', 'a'] });
  expect(result.current).toBe(false);
  act(() => vi.advanceTimersByTime(60_000));
  expect(spy).toHaveBeenCalledTimes(2);
});

test.each(['ready', 'none', undefined] as const)('does nothing when %s', (status) => {
  const { result } = setup({ status, key: ['recipe', 'a'] });
  expect(result.current).toBe(false);
  act(() => vi.advanceTimersByTime(60_000));
  expect(spy).not.toHaveBeenCalled();
});

test('a different recipe starts a fresh count', () => {
  const { result, rerender } = setup();
  act(() => vi.advanceTimersByTime(120_000));
  expect(spy).toHaveBeenCalledTimes(12);
  expect(result.current).toBe(false);
  rerender({ status: 'pending', key: ['recipe', 'b'] });
  expect(result.current).toBe(true);
  act(() => vi.advanceTimersByTime(10_000));
  expect(spy).toHaveBeenLastCalledWith({ queryKey: ['recipe', 'b'] });
  act(() => vi.advanceTimersByTime(110_000));
  expect(spy).toHaveBeenCalledTimes(24);
  expect(result.current).toBe(false);
});

test('stops on unmount', () => {
  const { unmount } = setup();
  act(() => vi.advanceTimersByTime(10_000));
  unmount();
  act(() => vi.advanceTimersByTime(60_000));
  expect(spy).toHaveBeenCalledTimes(1);
});
