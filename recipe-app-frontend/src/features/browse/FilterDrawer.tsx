import { useEffect, useId, useMemo, useRef, useState, type ChangeEvent, type ReactNode } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { useQuery } from '@tanstack/react-query';
import { ChevronRight, CircleAlert, Star, X } from 'lucide-react';
import { cuisinesQueryKey, getCuisines, getSeas, seasQueryKey } from '@/api/cuisines';
import { browseRecipes } from '@/api/recipes';
import { Button, Chip } from '@/components/ui';
import { countActive, emptyFilters, readMinutes, readRating, toApiFilter, type FilterState } from './filterParams';
import { CuisineAZ } from './CuisineAZ';
import { SeaCard } from './SeaCard';
import { DIETS, REGIONS, SEAS_FALLBACK, humanizeKey } from './regions';

const POPULAR_COUNT = 10;
const COUNT_DEBOUNCE_MS = 300;
const TIME_PICKS = [15, 30, 60];

const toggleIn = (list: string[], key: string) =>
  list.includes(key) ? list.filter((k) => k !== key) : [...list, key];

function useDebounced<T>(value: T, ms: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return debounced;
}

/**
 * The browse filters as a Radix Dialog: a right-side drawer from 640px up, a bottom sheet below.
 * Edits stay in a local draft (re-initialised from `applied` on every open) until "Show N recipes".
 */
export function FilterDrawer({
  open,
  onOpenChange,
  applied,
  query,
  onApply,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  applied: FilterState;
  /** The applied search text, so the live count matches what the grid will show. */
  query: string;
  onApply: (s: FilterState) => void;
}) {
  // Radix only returns focus to a Dialog.Trigger; the Filters button lives in BrowsePage, so restore it here.
  const returnFocus = useRef<HTMLElement | null>(null);
  const contentRef = useRef<HTMLDivElement>(null);

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 animate-overlay-in bg-scrim data-[state=closed]:animate-overlay-out" />
        <Dialog.Content
          ref={contentRef}
          onOpenAutoFocus={(e) => {
            returnFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
            // Focus the panel itself: the title is announced and Tab starts at the top.
            e.preventDefault();
            contentRef.current?.focus();
          }}
          onCloseAutoFocus={(e) => {
            e.preventDefault();
            returnFocus.current?.focus();
          }}
          className="fixed inset-x-0 bottom-0 z-50 flex max-h-[92dvh] animate-sheet-in flex-col rounded-t-card border border-rule bg-surface shadow-float outline-none data-[state=closed]:animate-sheet-out sm:inset-y-0 sm:right-0 sm:left-auto sm:max-h-none sm:w-[27rem] sm:animate-drawer-in sm:rounded-none sm:rounded-l-card sm:data-[state=closed]:animate-drawer-out"
        >
          <DrawerPanel
            applied={applied}
            query={query}
            onApply={(s) => {
              onApply(s);
              onOpenChange(false);
            }}
          />
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

/** Mounted fresh on every open, so its state is the draft. */
function DrawerPanel({
  applied,
  query,
  onApply,
}: {
  applied: FilterState;
  query: string;
  onApply: (s: FilterState) => void;
}) {
  const ids = useId();
  const [draft, setDraft] = useState<FilterState>(applied);
  const [timeText, setTimeText] = useState(applied.maxReadyInMinutes?.toString() ?? '');
  const [ratingText, setRatingText] = useState(applied.minRating?.toString() ?? '');
  const [timeBad, setTimeBad] = useState(false); // browser-rejected input such as "1e" (value reads as "")
  const [ratingBad, setRatingBad] = useState(false);
  const [view, setView] = useState<'main' | 'az'>('main');
  const [backFromAz, setBackFromAz] = useState(false);
  const bodyRef = useRef<HTMLDivElement>(null);
  const azLinkRef = useRef<HTMLButtonElement>(null);

  const cuisines = useQuery({ queryKey: cuisinesQueryKey, queryFn: getCuisines, staleTime: 60_000 });

  // Sea labels and info cards. A failing endpoint falls back to the built-in chips and shows no cards.
  const seas = useQuery({ queryKey: seasQueryKey, queryFn: getSeas, staleTime: Infinity });
  const seaChips = seas.data ?? SEAS_FALLBACK;
  const selectedSeas = (seas.data ?? []).filter((s) => draft.sea.includes(s.key));

  // Live count, debounced so typing and tapping don't fire a request per keystroke.
  const countFor = useDebounced(draft, COUNT_DEBOUNCE_MS);
  const count = useQuery({
    queryKey: ['recipeCount', countFor, query],
    queryFn: () => browseRecipes(toApiFilter(countFor), 0, 1, 'newest', query),
    staleTime: 30_000,
  });
  const settled = JSON.stringify(countFor) === JSON.stringify(draft);
  const total = settled && count.isSuccess ? count.data.page.totalElements : null;
  const showLabel = total === null ? 'Show recipes' : `Show ${total} ${total === 1 ? 'recipe' : 'recipes'}`;

  const withRecipes = useMemo(
    () => [...(cuisines.data ?? [])].filter((c) => c.count > 0).sort((a, b) => b.count - a.count),
    [cuisines.data],
  );
  const popular = withRecipes.slice(0, POPULAR_COUNT);
  const catalogue = useMemo(() => new Map((cuisines.data ?? []).map((c) => [c.key, c])), [cuisines.data]);
  // Cuisines ticked in the A–Z view (or from the URL) that aren't in the popular ten still show here.
  const extraSelected = draft.cuisine.filter((k) => !popular.some((c) => c.key === k));

  const timeInvalid = timeBad || (timeText !== '' && readMinutes(timeText) === null);
  const ratingInvalid = ratingBad || (ratingText !== '' && readRating(ratingText) === null);
  const nothingToClear = countActive(draft) === 0 && timeText === '' && ratingText === '' && !timeBad && !ratingBad;

  useEffect(() => {
    if (bodyRef.current) bodyRef.current.scrollTop = 0;
    if (view === 'main' && backFromAz) azLinkRef.current?.focus();
  }, [view, backFromAz]);

  const patch = (p: Partial<FilterState>) => setDraft((d) => ({ ...d, ...p }));
  const toggleCuisine = (key: string) => setDraft((d) => ({ ...d, cuisine: toggleIn(d.cuisine, key) }));

  function setTime(text: string, bad = false) {
    setTimeText(text);
    setTimeBad(bad);
    patch({ maxReadyInMinutes: bad ? null : readMinutes(text) });
  }

  function setRating(text: string, bad = false) {
    setRatingText(text);
    setRatingBad(bad);
    patch({ minRating: bad ? null : readRating(text) });
  }

  const isBadInput = (e: ChangeEvent<HTMLInputElement>) => e.currentTarget.validity?.badInput === true;

  function clearAll() {
    setDraft(emptyFilters);
    setTimeText('');
    setRatingText('');
    setTimeBad(false);
    setRatingBad(false);
  }

  return (
    <>
      <div aria-hidden="true" className="mx-auto mt-2.5 h-1.5 w-10 shrink-0 rounded-full bg-rule sm:hidden" />

      <header className="flex shrink-0 items-center justify-between gap-3 px-5 pt-4 pb-3 sm:px-7 sm:pt-7">
        <Dialog.Title className="font-serif text-[28px] leading-none font-medium tracking-[-0.015em] text-ink">
          Filters
        </Dialog.Title>
        <Dialog.Description className="sr-only">
          Narrow the recipes by region, sea, cuisine, dish type, time and rating.
        </Dialog.Description>
        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={clearAll}
            disabled={nothingToClear}
            className="min-h-10 rounded-full px-3 py-1.5 text-[15px] font-semibold text-ink transition-colors hover:bg-tint disabled:pointer-events-none disabled:opacity-45"
          >
            Clear all
          </button>
          <Dialog.Close
            aria-label="Close filters"
            className="flex size-10 items-center justify-center rounded-full text-ink transition-colors hover:bg-tint"
          >
            <X aria-hidden="true" className="size-5" strokeWidth={2} />
          </Dialog.Close>
        </div>
      </header>

      <div ref={bodyRef} className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 pt-2 pb-6 sm:px-7">
        {view === 'az' ? (
          <CuisineAZ
            cuisines={withRecipes}
            selected={draft.cuisine}
            onToggle={toggleCuisine}
            onBack={() => {
              setBackFromAz(true);
              setView('main');
            }}
          />
        ) : (
          <div className={`space-y-8 ${backFromAz ? 'animate-view-back' : ''}`}>
            <Section title="Regions">
              <div className="flex flex-wrap gap-2">
                {REGIONS.map((r) => (
                  <Chip
                    key={r.key}
                    tone="accent"
                    selected={draft.region.includes(r.key)}
                    onClick={() => patch({ region: toggleIn(draft.region, r.key) })}
                  >
                    {r.label}
                  </Chip>
                ))}
              </div>
            </Section>

            <Section title="Seas">
              <div className="flex flex-wrap gap-2">
                {seaChips.map((s) => (
                  <Chip
                    key={s.key}
                    tone="accent"
                    selected={draft.sea.includes(s.key)}
                    onClick={() => patch({ sea: toggleIn(draft.sea, s.key) })}
                  >
                    {s.label}
                  </Chip>
                ))}
              </div>
              {selectedSeas.length > 0 && (
                <div className="mt-4 space-y-3">
                  {selectedSeas.map((s) => (
                    <SeaCard key={s.key} sea={s} />
                  ))}
                </div>
              )}
            </Section>

            <Section title="Popular cuisines">
              {cuisines.isPending ? (
                <div className="flex flex-wrap gap-2">
                  <span className="sr-only">Loading cuisines…</span>
                  {[5.5, 7, 6, 4.5, 6.5, 5].map((w, i) => (
                    <span
                      key={i}
                      aria-hidden="true"
                      style={{ width: `${w}rem` }}
                      className="h-10 animate-pulse rounded-full bg-tint"
                    />
                  ))}
                </div>
              ) : cuisines.isError ? (
                <div className="flex flex-wrap items-center gap-3 rounded-card border border-rule bg-page px-4 py-3 text-[15px] text-ink">
                  <CircleAlert aria-hidden="true" className="size-4 shrink-0 text-danger" />
                  <span className="flex-1">Couldn't load cuisines</span>
                  <Button type="button" variant="outline" className="!px-4 !py-1.5" onClick={() => void cuisines.refetch()}>
                    Retry
                  </Button>
                </div>
              ) : (
                <>
                  <div className="flex flex-wrap gap-2">
                    {popular.map((c) => (
                      <Chip key={c.key} selected={draft.cuisine.includes(c.key)} count={c.count} onClick={() => toggleCuisine(c.key)}>
                        {c.label}
                      </Chip>
                    ))}
                    {extraSelected.map((key) => (
                      <Chip key={key} selected count={catalogue.get(key)?.count} onClick={() => toggleCuisine(key)}>
                        {catalogue.get(key)?.label ?? humanizeKey(key)}
                      </Chip>
                    ))}
                  </div>
                  {withRecipes.length === 0 ? (
                    <p className="text-[15px] text-ink-soft">No cuisines have recipes yet.</p>
                  ) : (
                    <button
                      ref={azLinkRef}
                      type="button"
                      onClick={() => setView('az')}
                      className="mt-2 -ml-1 inline-flex min-h-10 items-center gap-1 rounded-full px-1 py-1 text-[15px] font-semibold text-accent-ink hover:underline"
                    >
                      All cuisines A–Z ({withRecipes.length})
                      <ChevronRight aria-hidden="true" className="size-4" strokeWidth={2.25} />
                    </button>
                  )}
                </>
              )}
            </Section>

            <Section title="Dish type">
              <div className="flex flex-wrap gap-2">
                {DIETS.map((d) => (
                  <Chip key={d.key} selected={draft[d.key]} onClick={() => patch({ [d.key]: !draft[d.key] })}>
                    {d.label}
                  </Chip>
                ))}
              </div>
            </Section>

            <Section title="Time & rating">
              <div className="space-y-5">
                <NumberField
                  id={`${ids}-time`}
                  label="Max time (minutes)"
                  suffix="min"
                  value={timeText}
                  invalid={timeInvalid}
                  hint="Enter whole minutes from 1 to 600"
                  inputProps={{ min: 1, max: 600, step: 1, inputMode: 'numeric', placeholder: 'Any' }}
                  onChange={(e) => setTime(e.target.value, isBadInput(e))}
                >
                  <div className="flex gap-1.5">
                    {TIME_PICKS.map((m) => {
                      const on = draft.maxReadyInMinutes === m;
                      return (
                        <button
                          key={m}
                          type="button"
                          aria-pressed={on}
                          onClick={() => setTime(on ? '' : String(m))}
                          className={`min-h-10 rounded-full border px-3 py-1.5 text-[13px] font-semibold whitespace-nowrap tabular-nums transition-colors ${
                            on ? 'border-ink bg-ink text-on-ink' : 'border-field bg-surface text-ink hover:bg-tint'
                          }`}
                        >
                          {m} min
                        </button>
                      );
                    })}
                  </div>
                </NumberField>

                <NumberField
                  id={`${ids}-rating`}
                  label="Min rating"
                  value={ratingText}
                  invalid={ratingInvalid}
                  hint="Enter 1 to 5, in steps of 0.5"
                  inputProps={{ min: 1, max: 5, step: 0.5, inputMode: 'decimal', placeholder: 'Any' }}
                  onChange={(e) => setRating(e.target.value, isBadInput(e))}
                >
                  <div className="flex items-center gap-2">
                    <StarPreview value={draft.minRating} />
                    {draft.minRating !== null && <span className="text-[13px] text-ink-soft">and up</span>}
                  </div>
                </NumberField>
              </div>
            </Section>
          </div>
        )}
      </div>

      <footer className="shrink-0 border-t border-rule bg-surface px-5 pt-4 pb-[max(1rem,env(safe-area-inset-bottom))] sm:px-7 sm:pb-6">
        <Button type="button" className="w-full" onClick={() => onApply(draft)}>
          {showLabel}
        </Button>
        <span role="status" className="sr-only">
          {total === null ? '' : `${total} ${total === 1 ? 'recipe matches' : 'recipes match'}`}
        </span>
      </footer>
    </>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section>
      <h3 className="mb-3 text-[15px] font-semibold text-ink">{title}</h3>
      {children}
    </section>
  );
}

function NumberField({
  id,
  label,
  suffix,
  value,
  invalid,
  hint,
  inputProps,
  onChange,
  children,
}: {
  id: string;
  label: string;
  suffix?: string;
  value: string;
  invalid: boolean;
  hint: string;
  inputProps: { min: number; max: number; step: number; inputMode: 'numeric' | 'decimal'; placeholder: string };
  onChange: (e: ChangeEvent<HTMLInputElement>) => void;
  children?: ReactNode;
}) {
  return (
    <div>
      <label htmlFor={id} className="mb-1.5 block text-[15px] font-semibold text-ink">
        {label}
      </label>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <div className="relative w-28 shrink-0">
          <input
            id={id}
            type="number"
            value={value}
            onChange={onChange}
            aria-invalid={invalid}
            aria-describedby={invalid ? `${id}-hint` : undefined}
            {...inputProps}
            className={`w-full rounded-lg border bg-surface py-2.5 pl-3.5 text-base text-ink tabular-nums transition [appearance:textfield] placeholder:text-ink-soft focus:ring-4 focus:outline-none sm:text-[15px] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none ${
              suffix ? 'pr-11' : 'pr-3.5'
            } ${
              invalid
                ? 'border-danger focus:border-danger focus:ring-danger/15'
                : 'border-field hover:border-ink-soft focus:border-ink focus:ring-ink/15'
            }`}
          />
          {suffix && (
            <span aria-hidden="true" className="pointer-events-none absolute top-1/2 right-3.5 -translate-y-1/2 text-[13px] font-medium text-ink-soft">
              {suffix}
            </span>
          )}
        </div>
        {children}
      </div>
      {invalid && (
        <p id={`${id}-hint`} role="alert" className="mt-1.5 text-[13px] font-medium text-danger">
          {hint}
        </p>
      )}
    </div>
  );
}

/** Five stars filled to the typed minimum rating, in half steps. Decorative: the input carries the value. */
function StarPreview({ value }: { value: number | null }) {
  return (
    <span aria-hidden="true" className="flex items-center gap-0.5">
      {[1, 2, 3, 4, 5].map((i) => {
        const fill = value === null ? 0 : value >= i ? 100 : value >= i - 0.5 ? 50 : 0;
        return (
          <span key={i} className="relative size-4">
            <Star className="absolute inset-0 size-4 fill-tint text-field" strokeWidth={1.5} />
            {fill > 0 && (
              <span className="absolute inset-y-0 left-0 overflow-hidden" style={{ width: `${fill}%` }}>
                <Star className="size-4 fill-accent text-accent" strokeWidth={1.5} />
              </span>
            )}
          </span>
        );
      })}
    </span>
  );
}
