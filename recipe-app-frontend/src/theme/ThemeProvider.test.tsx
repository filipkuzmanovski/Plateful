import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ThemeProvider, useTheme } from './ThemeProvider';

function Consumer() {
  const { choice, resolved, setChoice } = useTheme();
  return (
    <div>
      <p data-testid="state">{`${choice}/${resolved}`}</p>
      <button onClick={() => setChoice('light')}>light</button>
      <button onClick={() => setChoice('dark')}>dark</button>
      <button onClick={() => setChoice('system')}>system</button>
    </div>
  );
}

function stubMatchMedia(initial: boolean) {
  const state = { matches: initial, listener: null as null | (() => void) };
  vi.stubGlobal('matchMedia', (query: string) => ({
    get matches() {
      return state.matches;
    },
    media: query,
    addEventListener: (_: string, fn: () => void) => {
      state.listener = fn;
    },
    removeEventListener: () => {
      state.listener = null;
    },
  }));
  return state;
}

const html = () => document.documentElement;

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('ThemeProvider', () => {
  it('starts from the stored choice and applies it', () => {
    localStorage.setItem('plateful.theme', 'dark');
    render(<ThemeProvider><Consumer /></ThemeProvider>);
    expect(html().dataset.theme).toBe('dark');
    expect(screen.getByTestId('state')).toHaveTextContent('dark/dark');
  });

  it('follows the device while on system', () => {
    const mq = stubMatchMedia(false);
    render(<ThemeProvider><Consumer /></ThemeProvider>);
    expect(html().dataset.theme).toBe('light');
    act(() => {
      mq.matches = true;
      mq.listener?.();
    });
    expect(html().dataset.theme).toBe('dark');
    expect(screen.getByTestId('state')).toHaveTextContent('system/dark');
  });

  it('ignores the device when a mode is chosen', async () => {
    const mq = stubMatchMedia(false);
    render(<ThemeProvider><Consumer /></ThemeProvider>);
    await userEvent.click(screen.getByRole('button', { name: 'light' }));
    expect(localStorage.getItem('plateful.theme')).toBe('light');
    act(() => {
      mq.matches = true;
      mq.listener?.();
    });
    expect(html().dataset.theme).toBe('light');
  });

  it('applies a choice even when storage throws', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    render(<ThemeProvider><Consumer /></ThemeProvider>);
    await userEvent.click(screen.getByRole('button', { name: 'dark' }));
    expect(html().dataset.theme).toBe('dark');
    expect(screen.getByTestId('state')).toHaveTextContent('dark/dark');
  });

  it('syncs from another tab', () => {
    render(<ThemeProvider><Consumer /></ThemeProvider>);
    act(() => {
      localStorage.setItem('plateful.theme', 'dark');
      window.dispatchEvent(new StorageEvent('storage', { key: 'plateful.theme', newValue: 'dark' }));
    });
    expect(screen.getByTestId('state')).toHaveTextContent('dark/dark');
    expect(html().dataset.theme).toBe('dark');
  });

  it('renders when matchMedia is missing', () => {
    vi.stubGlobal('matchMedia', undefined);
    expect(() => render(<ThemeProvider><Consumer /></ThemeProvider>)).not.toThrow();
    expect(html().dataset.theme).toBe('light');
  });

  it('useTheme throws outside the provider', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => render(<Consumer />)).toThrow(/ThemeProvider/);
  });
});
