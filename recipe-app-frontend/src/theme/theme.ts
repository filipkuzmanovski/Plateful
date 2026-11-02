export type ThemeChoice = 'system' | 'light' | 'dark';
export type ResolvedTheme = 'light' | 'dark';

export const THEME_STORAGE_KEY = 'plateful.theme';

const DARK_QUERY = '(prefers-color-scheme: dark)';

/** The stored choice; a missing, invalid or unreadable value means "match my device". */
export function readThemeChoice(): ThemeChoice {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY);
    return stored === 'light' || stored === 'dark' ? stored : 'system';
  } catch {
    return 'system';
  }
}

/** Stores the choice. "system" is the default, so it clears the key. Storage errors are ignored. */
export function writeThemeChoice(choice: ThemeChoice): void {
  try {
    if (choice === 'system') localStorage.removeItem(THEME_STORAGE_KEY);
    else localStorage.setItem(THEME_STORAGE_KEY, choice);
  } catch {
    // Private mode or blocked storage: the choice still applies for this page view.
  }
}

export function systemPrefersDark(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(DARK_QUERY).matches;
}

export function resolveTheme(choice: ThemeChoice, prefersDark: boolean): ResolvedTheme {
  if (choice === 'system') return prefersDark ? 'dark' : 'light';
  return choice;
}

/** The page colour per mode (the `page` token); the browser chrome follows the chosen mode, not the device's. */
const THEME_COLOR: Record<ResolvedTheme, string> = { light: '#fbfbf7', dark: '#161a14' };

export function applyTheme(resolved: ResolvedTheme): void {
  document.documentElement.dataset.theme = resolved;
  document.documentElement.style.colorScheme = resolved;
  // index.html has one theme-color tag per device mode; both are set so an explicit choice wins.
  document.querySelectorAll('meta[name="theme-color"]').forEach((meta) => {
    meta.setAttribute('content', THEME_COLOR[resolved]);
  });
}
