import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useState, type ReactNode } from 'react';
import {
  THEME_STORAGE_KEY,
  applyTheme,
  readThemeChoice,
  resolveTheme,
  systemPrefersDark,
  writeThemeChoice,
  type ResolvedTheme,
  type ThemeChoice,
} from './theme';

interface ThemeState {
  choice: ThemeChoice;
  resolved: ResolvedTheme;
  setChoice: (choice: ThemeChoice) => void;
}

const ThemeContext = createContext<ThemeState | null>(null);

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [choice, setChoiceState] = useState<ThemeChoice>(readThemeChoice);
  const [prefersDark, setPrefersDark] = useState(systemPrefersDark);
  const resolved = resolveTheme(choice, prefersDark);

  useLayoutEffect(() => {
    applyTheme(resolved);
  }, [resolved]);

  // While following the device, track its colour scheme.
  useEffect(() => {
    if (choice !== 'system') return;
    const mql = typeof window.matchMedia === 'function' ? window.matchMedia('(prefers-color-scheme: dark)') : undefined;
    const onChange = () => setPrefersDark(systemPrefersDark());
    onChange();
    mql?.addEventListener?.('change', onChange);
    return () => mql?.removeEventListener?.('change', onChange);
  }, [choice]);

  // Another tab changed the choice.
  useEffect(() => {
    const onStorage = (e: StorageEvent) => {
      if (e.key === THEME_STORAGE_KEY) setChoiceState(readThemeChoice());
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);

  const setChoice = useCallback((next: ThemeChoice) => {
    writeThemeChoice(next);
    setChoiceState(next);
  }, []);

  const value = useMemo(() => ({ choice, resolved, setChoice }), [choice, resolved, setChoice]);
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme(): ThemeState {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useTheme must be used within ThemeProvider');
  return ctx;
}
