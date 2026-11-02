import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { Check, ChevronLeft, Search } from 'lucide-react';
import type { CuisineInfo } from '@/api/cuisines';

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');

/** "Émirati" files under E; anything not starting with a Latin letter under "#". */
function letterOf(label: string): string {
  const first = label.normalize('NFD').charAt(0).toUpperCase();
  return first >= 'A' && first <= 'Z' ? first : '#';
}

/**
 * Every cuisine that has at least one recipe, grouped A–Z, with a search box and a letter index.
 * Rendered inside the filter drawer, so ticks land in the drawer's draft.
 */
export function CuisineAZ({
  cuisines,
  selected,
  onToggle,
  onBack,
}: {
  cuisines: CuisineInfo[];
  selected: string[];
  onToggle: (key: string) => void;
  onBack: () => void;
}) {
  const baseId = useId();
  const [query, setQuery] = useState('');
  const searchRef = useRef<HTMLInputElement>(null);

  useEffect(() => searchRef.current?.focus(), []);

  const groups = useMemo(() => {
    const q = query.trim().toLowerCase();
    const visible = cuisines
      .filter((c) => c.count > 0 && c.label.toLowerCase().includes(q))
      .sort((a, b) => a.label.localeCompare(b.label));
    const byLetter = new Map<string, CuisineInfo[]>();
    for (const c of visible) {
      const letter = letterOf(c.label);
      byLetter.set(letter, [...(byLetter.get(letter) ?? []), c]);
    }
    return [...byLetter.entries()].sort(([a], [b]) => (a === '#' ? 1 : b === '#' ? -1 : a.localeCompare(b)));
  }, [cuisines, query]);

  const present = new Set(groups.map(([letter]) => letter));
  const groupId = (letter: string) => `${baseId}-letter-${letter === '#' ? 'other' : letter}`;

  function jumpTo(letter: string) {
    const group = document.getElementById(groupId(letter));
    group?.scrollIntoView?.({ block: 'start', behavior: 'smooth' });
    group?.querySelector<HTMLInputElement>('input[type="checkbox"]')?.focus({ preventScroll: true });
  }

  return (
    <div className="animate-view-in">
      <div className="flex items-center justify-between gap-2 pb-3">
        <button
          type="button"
          onClick={onBack}
          className="-ml-2 inline-flex min-h-10 items-center gap-0.5 rounded-full py-1.5 pr-3 pl-1.5 text-[15px] font-semibold text-ink transition-colors hover:bg-tint"
        >
          <ChevronLeft aria-hidden="true" className="size-4" strokeWidth={2.25} />
          Back
        </button>
        <h3 className="font-serif text-lg font-semibold tracking-[-0.015em] text-ink">All cuisines</h3>
        <button
          type="button"
          onClick={onBack}
          className="-mr-2 min-h-10 rounded-full px-3 py-1.5 text-[15px] font-semibold text-ink-soft transition-colors hover:bg-tint hover:text-ink"
        >
          Done
        </button>
      </div>

      <div className="sticky top-0 z-10 -mx-1 bg-surface px-1 pt-1 pb-3">
        <label htmlFor={`${baseId}-search`} className="sr-only">
          Search cuisines
        </label>
        <div className="relative">
          <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-3.5 size-4 -translate-y-1/2 text-ink-soft" />
          <input
            id={`${baseId}-search`}
            ref={searchRef}
            type="search"
            autoComplete="off"
            placeholder="Search cuisines"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            className="w-full rounded-full border border-field bg-surface py-2.5 pr-4 pl-10 text-base text-ink transition placeholder:text-ink-soft hover:border-ink-soft focus:border-ink focus:ring-4 focus:ring-ink/15 focus:outline-none sm:text-[15px]"
          />
        </div>
      </div>

      {groups.length === 0 ? (
        <p className="py-10 text-center text-[15px] text-ink-soft">
          {query.trim() ? `No cuisines match “${query.trim()}”` : 'No cuisines have recipes yet'}
        </p>
      ) : (
        <div className="flex gap-3">
          <div className="min-w-0 flex-1">
            {groups.map(([letter, items]) => (
              <section key={letter} id={groupId(letter)} aria-labelledby={`${groupId(letter)}-h`} className="scroll-mt-16 not-first:mt-4">
                <h4
                  id={`${groupId(letter)}-h`}
                  className="mb-1 font-serif text-lg leading-none font-semibold text-accent-strong"
                >
                  {letter}
                </h4>
                <ul>
                  {items.map((c) => {
                    const checked = selected.includes(c.key);
                    const inputId = `${baseId}-c-${c.key}`;
                    return (
                      <li key={c.key} className="border-b border-rule last:border-b-0">
                        <label
                          htmlFor={inputId}
                          className="group flex cursor-pointer items-center gap-3 min-h-10 rounded-lg px-1.5 py-2.5 transition-colors hover:bg-tint"
                        >
                          <span className="relative flex size-[1.125rem] shrink-0 items-center justify-center">
                            <input
                              id={inputId}
                              type="checkbox"
                              checked={checked}
                              onChange={() => onToggle(c.key)}
                              aria-describedby={`${inputId}-n`}
                              className="peer size-full cursor-pointer appearance-none rounded-[5px] border-[1.5px] border-field bg-surface transition checked:border-ink checked:bg-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ink"
                            />
                            <Check
                              aria-hidden="true"
                              strokeWidth={3}
                              className="pointer-events-none absolute size-3 text-on-ink opacity-0 transition peer-checked:opacity-100"
                            />
                          </span>
                          <span className={`flex-1 text-[15px] text-ink ${checked ? 'font-semibold' : ''}`}>{c.label}</span>
                          <span aria-hidden="true" className="text-[13px] text-ink-soft tabular-nums">
                            {c.count}
                          </span>
                        </label>
                        <span id={`${inputId}-n`} hidden>
                          {c.count === 1 ? '1 recipe' : `${c.count} recipes`}
                        </span>
                      </li>
                    );
                  })}
                </ul>
              </section>
            ))}
          </div>

          <nav aria-label="Jump to letter" className="sticky top-16 self-start">
            <ul className="flex flex-col items-center">
              {[...ALPHABET, ...(present.has('#') ? ['#'] : [])].map((letter) =>
                present.has(letter) ? (
                  <li key={letter}>
                    <button
                      type="button"
                      onClick={() => jumpTo(letter)}
                      className="flex h-5 w-8 items-center justify-center rounded text-[13px] font-bold text-ink transition-colors hover:bg-tint hover:text-accent-ink"
                    >
                      {letter}
                    </button>
                  </li>
                ) : (
                  <li key={letter} aria-hidden="true" className="flex h-5 w-8 items-center justify-center text-[13px] text-ink-soft/40">
                    {letter}
                  </li>
                ),
              )}
            </ul>
          </nav>
        </div>
      )}
    </div>
  );
}
