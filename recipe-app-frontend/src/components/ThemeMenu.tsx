import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { Check, Monitor, Moon, Sun } from 'lucide-react';
import { useTheme } from '@/theme/ThemeProvider';
import type { ThemeChoice } from '@/theme/theme';

const OPTIONS: { value: ThemeChoice; label: string; Icon: typeof Sun }[] = [
  { value: 'system', label: 'Match my device', Icon: Monitor },
  { value: 'light', label: 'Light', Icon: Sun },
  { value: 'dark', label: 'Dark', Icon: Moon },
];

/** The header's colour theme control: an icon button that opens a menu of Match my device, Light and Dark. */
export default function ThemeMenu() {
  const { choice, setChoice } = useTheme();
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const itemRefs = useRef<(HTMLButtonElement | null)[]>([]);

  const current = OPTIONS.find((o) => o.value === choice) ?? OPTIONS[0];

  // Close on an outside click or Escape; Escape also hands focus back to the button.
  useEffect(() => {
    if (!open) return;
    const onPointer = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      setOpen(false);
      buttonRef.current?.focus();
    };
    document.addEventListener('mousedown', onPointer);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onPointer);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  // Focus starts on the checked item.
  useEffect(() => {
    if (open) itemRefs.current[OPTIONS.findIndex((o) => o.value === choice)]?.focus();
    // Only when the menu opens, not when the choice changes while it is open.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const choose = (value: ThemeChoice) => {
    setChoice(value);
    setOpen(false);
    buttonRef.current?.focus();
  };

  const onMenuKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const last = OPTIONS.length - 1;
    const at = itemRefs.current.findIndex((el) => el === document.activeElement);
    let next: number | null = null;
    if (e.key === 'ArrowDown') next = at >= last ? 0 : at + 1;
    else if (e.key === 'ArrowUp') next = at <= 0 ? last : at - 1;
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = last;
    else if (e.key === 'Tab') setOpen(false);
    if (next !== null) {
      e.preventDefault();
      itemRefs.current[next]?.focus();
    }
  };

  return (
    <div ref={wrapRef} className="relative">
      <button
        ref={buttonRef}
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`Theme: ${current.label}`}
        className="flex size-10 items-center justify-center rounded-full text-ink-soft transition-colors hover:bg-tint hover:text-ink"
      >
        <current.Icon aria-hidden="true" className="size-5" />
      </button>
      {open && (
        <div
          role="menu"
          aria-label="Theme"
          onKeyDown={onMenuKeyDown}
          className="absolute right-0 z-40 mt-2 w-56 origin-top-right rounded-card border border-rule bg-surface p-1.5 text-left shadow-float"
        >
          {OPTIONS.map(({ value, label, Icon }, i) => (
            <button
              key={value}
              ref={(el) => {
                itemRefs.current[i] = el;
              }}
              type="button"
              role="menuitemradio"
              aria-checked={choice === value}
              tabIndex={-1}
              onClick={() => choose(value)}
              className="flex min-h-11 w-full items-center gap-2.5 rounded-lg px-3 py-2 text-left text-[15px] text-ink transition-colors hover:bg-tint focus-visible:bg-tint"
            >
              <Icon aria-hidden="true" className="size-4 text-ink-soft" />
              <span className="flex-1">{label}</span>
              {choice === value && <Check aria-hidden="true" className="size-4 text-ink" />}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
