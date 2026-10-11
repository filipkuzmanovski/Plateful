import { useEffect, useId, useMemo, useRef, useState, type ChangeEvent, type ReactNode } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { useQuery } from '@tanstack/react-query';
import { ChevronRight, CircleAlert, Star, X } from 'lucide-react';
import { cuisinesQueryKey, getCuisines } from '@/api/cuisines';
import { browseRecipes } from '@/api/recipes';
import { Button, Chip } from '@/components/ui';
import { countActive, emptyFilters, readMinutes, readRating, toApiFilter, type FilterState } from './filterParams';
import { CuisineAZ } from './CuisineAZ';
import { DIETS, REGIONS, humanizeKey } from './regions';

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
  onApply,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  applied: FilterState;
  onApply: (s: FilterState) => void;
}) {
  // Radix only returns focus to a Dialog.Trigger; the Filters button lives in BrowsePage, so restore it here.
  const returnFocus = useRef<HTMLElement | null>(null);
  const contentRef = useRef<HTMLDivElement>(null);

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 animate-overlay-in bg-olive/35 backdrop-blur-[2px] data-[state=closed]:animate-overlay-out" />
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
          className="fixed inset-x-0 bottom-0 z-50 flex max-h-[92dvh] animate-sheet-in flex-col rounded-t-[1.75rem] bg-paper shadow-lift ring-1 ring-sand/70 outline-none data-[state=closed]:animate-sheet-out sm:inset-y-0 sm:right-0 sm:left-auto sm:max-h-none sm:w-[27rem] sm:animate-drawer-in sm:rounded-none sm:rounded-l-[1.75rem] sm:data-[state=closed]:animate-drawer-out"
        >
          <DrawerPanel
            applied={applied}
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
function DrawerPanel({ applied, onApply }: { applied: FilterState; onApply: (s: FilterState) => void }) {
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

  // Live count, debounced so typing and tapping don't fire a request per keystroke.
  const countFor = useDebounced(draft, COUNT_DEBOUNCE_MS);
  const count = useQuery({
    queryKey: ['recipeCount', countFor],
    queryFn: () => browseRecipes(toApiFilter(countFor), 0, 1),
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
      <div aria-hidden="true" className="mx-auto mt-2.5 h-1.5 w-10 shrink-0 rounded-full bg-sand sm:hidden" />

      <header className="flex shrink-0 items-center justify-between gap-3 px-5 pt-4 pb-3 sm:px-7 sm:pt-7">
        <Dialog.Title className="font-serif text-[1.75rem] leading-none font-semibold tracking-tight text-olive">
          Filters
        </Dialog.Title>
        <Dialog.Description className="sr-only">
          Narrow the recipes by region, cuisine, dish type, time and rating.
        </Dialog.Description>
        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={clearAll}
            disabled={nothingToClear}
            className="rounded-full px-3 py-1.5 text-sm font-semibold text-olive underline-offset-4 transition hover:bg-sand/60 focus-visible:outline-olive disabled:pointer-events-none disabled:text-olive/45"
          >
            Clear all
          </button>
          <Dialog.Close
            aria-label="Close filters"
            className="flex size-9 items-center justify-center rounded-full text-olive transition hover:bg-sand/60 focus-visible:outline-olive"
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
                    tone="saffron"
                    selected={draft.region.includes(r.key)}
                    onClick={() => patch({ region: toggleIn(draft.region, r.key) })}
                  >
                    {r.label}
                  </Chip>
                ))}
              </div>
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
                      className="h-[2.125rem] animate-pulse rounded-full bg-sand/60"
                    />
                  ))}
                </div>
              ) : cuisines.isError ? (
                <div className="flex flex-wrap items-center gap-3 rounded-2xl border border-sand bg-cream px-4 py-3 text-sm text-olive">
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
                    <p className="text-sm text-olive/70">No cuisines have recipes yet.</p>
                  ) : (
                    <button
                      ref={azLinkRef}
                      type="button"
                      onClick={() => setView('az')}
                      className="group mt-3.5 -ml-1 inline-flex items-center gap-1 rounded-full px-1 py-1 text-sm font-semibold text-terracotta-deep transition focus-visible:outline-olive"
                    >
                      All cuisines A–Z ({withRecipes.length})
                      <ChevronRight aria-hidden="true" className="size-4 transition-transform group-hover:translate-x-0.5" strokeWidth={2.25} />
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
                          className={`rounded-full border px-2.5 py-1.5 text-xs font-semibold whitespace-nowrap tabular-nums transition focus-visible:outline-olive ${
                            on ? 'border-olive bg-olive text-cream' : 'border-sand bg-paper text-olive hover:border-olive/40'
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
                    {draft.minRating !== null && <span className="text-xs text-olive/70">and up</span>}
                  </div>
                </NumberField>
              </div>
            </Section>
          </div>
        )}
      </div>

      <footer className="shrink-0 border-t border-sand/70 bg-paper px-5 pt-4 pb-[max(1rem,env(safe-area-inset-bottom))] sm:px-7 sm:pb-6">
        <Button type="button" className="w-full !py-3 text-[15px]" onClick={() => onApply(draft)}>
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
      <h3 className="mb-3 text-[11px] font-semibold tracking-[0.14em] text-olive/70 uppercase">{title}</h3>
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
      <label htmlFor={id} className="mb-1.5 block text-[13px] font-semibold text-olive">
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
            className={`w-full rounded-xl border bg-paper py-2.5 pl-3.5 text-sm text-olive tabular-nums shadow-[inset_0_1px_2px_rgb(47_58_43/0.04)] transition [appearance:textfield] placeholder:text-olive/70 focus:ring-4 focus:outline-none [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none ${
              suffix ? 'pr-11' : 'pr-3.5'
            } ${
              invalid
                ? 'border-danger focus:border-danger focus:ring-danger/15'
                : 'border-sand hover:border-olive/30 focus:border-olive/50 focus:ring-saffron/20'
            }`}
          />
          {suffix && (
            <span aria-hidden="true" className="pointer-events-none absolute top-1/2 right-3.5 -translate-y-1/2 text-xs font-medium text-olive/70">
              {suffix}
            </span>
          )}
        </div>
        {children}
      </div>
      {invalid && (
        <p id={`${id}-hint`} role="alert" className="mt-1.5 text-xs font-medium text-danger">
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
            <Star className="absolute inset-0 size-4 fill-sand text-sand" strokeWidth={1.5} />
            {fill > 0 && (
              <span className="absolute inset-y-0 left-0 overflow-hidden" style={{ width: `${fill}%` }}>
                <Star className="size-4 fill-saffron text-saffron" strokeWidth={1.5} />
              </span>
            )}
          </span>
        );
      })}
    </span>
  );
}
