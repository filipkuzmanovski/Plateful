import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CircleAlert, X } from 'lucide-react';
import { cuisinesQueryKey, getCuisines } from '@/api/cuisines';
import { Button } from '@/components/ui';

const MAX_CUISINES = 10;
const MAX_VISIBLE_OPTIONS = 50;

const humanize = (key: string) =>
  key.replace(/[-_]/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());

/**
 * Searchable multi-select over the curated cuisine catalogue. Selected keys show as removable
 * chips; the combobox filters the remaining entries by label.
 */
export function CuisinePicker({
  value,
  onChange,
  error,
}: {
  value: string[];
  onChange: (keys: string[]) => void;
  error?: string;
}) {
  const inputId = useId();
  const listId = useId();
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const refocusOnReopen = useRef(false); // set when a chip removal frees a slot at the cap

  const cuisines = useQuery({ queryKey: cuisinesQueryKey, queryFn: getCuisines, staleTime: Infinity });
  const labelOf = useMemo(() => {
    const map = new Map((cuisines.data ?? []).map((c) => [c.key, c.label]));
    return (key: string) => map.get(key) ?? humanize(key);
  }, [cuisines.data]);

  const atMax = value.length >= MAX_CUISINES;
  const options = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (cuisines.data ?? [])
      .filter((c) => !value.includes(c.key) && c.label.toLowerCase().includes(q))
      .slice(0, MAX_VISIBLE_OPTIONS);
  }, [cuisines.data, value, query]);

  const showList = open && !atMax && cuisines.isSuccess;
  const activeIndex = Math.min(active, Math.max(options.length - 1, 0));
  const optionId = (key: string) => `${listId}-${key}`;

  // Keep the highlighted option visible when arrowing through a long list (no-op where unsupported, e.g. jsdom).
  const activeKey = showList ? options[activeIndex]?.key : undefined;
  useEffect(() => {
    if (activeKey) document.getElementById(optionId(activeKey))?.scrollIntoView?.({ block: 'nearest' });
  }, [activeKey]);

  // The input is disabled at the cap and can't take focus until it re-enables.
  useEffect(() => {
    if (!atMax && refocusOnReopen.current) {
      refocusOnReopen.current = false;
      inputRef.current?.focus();
    }
  }, [atMax]);

  function select(key: string) {
    onChange([...value, key]);
    setQuery('');
    setActive(0);
    // A disabled input fires no blur, so close explicitly or the list reopens itself later.
    if (value.length + 1 >= MAX_CUISINES) setOpen(false);
  }

  function remove(key: string) {
    if (atMax) refocusOnReopen.current = true;
    onChange(value.filter((k) => k !== key));
  }

  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setOpen(true);
      // A closed list opens on the first option; an open one moves down.
      setActive(showList ? Math.min(activeIndex + 1, options.length - 1) : 0);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive(Math.max(activeIndex - 1, 0));
    } else if (e.key === 'Enter') {
      // Never let Enter submit the surrounding form from this field.
      e.preventDefault();
      if (showList && options[activeIndex]) select(options[activeIndex].key);
    } else if (e.key === 'Escape') {
      setOpen(false);
    } else if (e.key === 'Backspace' && query === '' && value.length > 0) {
      onChange(value.slice(0, -1));
    }
  }

  return (
    <div>
      <label htmlFor={inputId} className="mb-2 block text-[13px] font-semibold text-olive">
        Cuisines
      </label>

      {value.length > 0 && (
        <ul className="mb-2.5 flex flex-wrap gap-2">
          {value.map((key) => (
            <li
              key={key}
              className="inline-flex items-center gap-1 rounded-full bg-olive py-1 pr-1 pl-3.5 text-sm font-medium text-cream shadow-button"
            >
              {labelOf(key)}
              <button
                type="button"
                aria-label={`Remove ${labelOf(key)}`}
                onClick={() => remove(key)}
                className="flex size-6 items-center justify-center rounded-full transition hover:bg-cream/20 focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-saffron"
              >
                <X aria-hidden="true" className="size-3.5" strokeWidth={2.5} />
              </button>
            </li>
          ))}
        </ul>
      )}

      {cuisines.isError ? (
        <div className="flex flex-wrap items-center gap-3 rounded-xl border border-sand bg-cream px-3.5 py-2.5 text-sm text-olive">
          <CircleAlert aria-hidden="true" className="size-4 shrink-0 text-danger" />
          <span>Couldn't load cuisines</span>
          <Button type="button" variant="outline" className="!px-3.5 !py-1" onClick={() => void cuisines.refetch()}>
            Retry
          </Button>
        </div>
      ) : (
        <div className="relative">
          <input
            id={inputId}
            ref={inputRef}
            role="combobox"
            aria-expanded={showList}
            aria-controls={listId}
            aria-autocomplete="list"
            aria-activedescendant={showList && options[activeIndex] ? optionId(options[activeIndex].key) : undefined}
            autoComplete="off"
            disabled={atMax || cuisines.isPending}
            placeholder={cuisines.isPending ? 'Loading cuisines…' : 'Search cuisines'}
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setActive(0);
              setOpen(true);
            }}
            onClick={() => setOpen(true)}
            onBlur={() => setOpen(false)}
            onKeyDown={onKeyDown}
            className="w-full rounded-xl border border-sand bg-paper px-3.5 py-2.5 text-sm text-olive shadow-[inset_0_1px_2px_rgb(47_58_43/0.04)] transition placeholder:text-olive/70 hover:border-olive/30 focus:border-olive/60 focus:ring-4 focus:ring-saffron/20 focus:outline-none disabled:cursor-not-allowed disabled:bg-cream disabled:opacity-70"
          />
          <ul
            id={listId}
            role="listbox"
            aria-label="Cuisine options"
            hidden={!showList}
            className="absolute z-20 mt-1.5 max-h-64 w-full overflow-auto rounded-xl bg-paper p-1.5 shadow-lift ring-1 ring-sand"
          >
            {options.length === 0 ? (
              <li role="presentation" className="px-3 py-2 text-sm text-olive/70">No matching cuisines</li>
            ) : (
              options.map((c, i) => (
                <li
                  key={c.key}
                  id={optionId(c.key)}
                  role="option"
                  aria-selected={i === activeIndex}
                  // mousedown (not click) so the input keeps focus and onBlur doesn't close the list first.
                  onMouseDown={(e) => {
                    e.preventDefault();
                    select(c.key);
                  }}
                  onMouseEnter={() => setActive(i)}
                  className={`cursor-pointer rounded-lg px-3 py-2 text-sm text-olive ${i === activeIndex ? 'bg-sand/70' : ''}`}
                >
                  {c.label}
                </li>
              ))
            )}
          </ul>
        </div>
      )}

      {atMax && <p className="mt-1.5 text-xs text-olive/70">Up to {MAX_CUISINES} cuisines</p>}
      {error && <p role="alert" className="mt-1.5 text-xs font-medium text-danger">{error}</p>}
    </div>
  );
}
