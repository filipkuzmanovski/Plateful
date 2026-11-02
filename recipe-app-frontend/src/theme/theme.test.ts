import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  THEME_STORAGE_KEY,
  applyTheme,
  readThemeChoice,
  resolveTheme,
  systemPrefersDark,
  writeThemeChoice,
} from './theme';

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('resolveTheme', () => {
  it('follows the device on system and honours an explicit choice', () => {
    expect(resolveTheme('system', true)).toBe('dark');
    expect(resolveTheme('system', false)).toBe('light');
    expect(resolveTheme('light', true)).toBe('light');
    expect(resolveTheme('dark', false)).toBe('dark');
  });
});

describe('readThemeChoice', () => {
  it('reads a stored choice and treats an invalid value as system', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'dark');
    expect(readThemeChoice()).toBe('dark');
    localStorage.setItem(THEME_STORAGE_KEY, 'purple');
    expect(readThemeChoice()).toBe('system');
  });

  it('is system when nothing is stored', () => {
    expect(readThemeChoice()).toBe('system');
  });

  it('falls back to system when storage throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(readThemeChoice()).toBe('system');
  });
});

describe('writeThemeChoice', () => {
  it('stores light and dark, and clears the key for system', () => {
    writeThemeChoice('light');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light');
    writeThemeChoice('system');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();
  });

  it('does not throw when storage throws', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('full');
    });
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(() => writeThemeChoice('dark')).not.toThrow();
    expect(() => writeThemeChoice('system')).not.toThrow();
  });
});

describe('applyTheme', () => {
  it('sets data-theme and color-scheme on the root element', () => {
    applyTheme('dark');
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(document.documentElement.style.colorScheme).toBe('dark');
    applyTheme('light');
    expect(document.documentElement.dataset.theme).toBe('light');
    expect(document.documentElement.style.colorScheme).toBe('light');
  });

  it('sets both theme-color metas to the resolved page colour', () => {
    const metas = ['light', 'dark'].map((mode) => {
      const meta = document.createElement('meta');
      meta.name = 'theme-color';
      meta.media = `(prefers-color-scheme: ${mode})`;
      document.head.appendChild(meta);
      return meta;
    });
    try {
      applyTheme('dark');
      expect(metas.map((m) => m.content)).toEqual(['#161a14', '#161a14']);
      applyTheme('light');
      expect(metas.map((m) => m.content)).toEqual(['#fbfbf7', '#fbfbf7']);
    } finally {
      metas.forEach((m) => m.remove());
    }
  });
});

describe('systemPrefersDark', () => {
  it('is false without matchMedia', () => {
    vi.stubGlobal('matchMedia', undefined);
    expect(systemPrefersDark()).toBe(false);
  });

  it('reads the media query when available', () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true }));
    expect(systemPrefersDark()).toBe(true);
  });
});
