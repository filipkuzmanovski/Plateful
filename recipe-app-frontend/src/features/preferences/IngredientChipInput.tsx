import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { X } from 'lucide-react';
import { suggestIngredients } from '@/api/food';

const SUGGEST_DEBOUNCE_MS = 300;
const MIN_QUERY_LENGTH = 2;
const MAX_NAME_LENGTH = 60;

const clean = (raw: string) => raw.trim().replace(/\s+/g, ' ').slice(0, MAX_NAME_LENGTH).trim();

/**
 * A free-text list of ingredient names. Enter or a comma adds the typed name as a removable chip;
 * suggestions from the ingredient catalogue appear (after a 300 ms pause) as buttons below the input.
 * Names are trimmed, capped at 60 characters and compared case-insensitively. At `max` the input is
 * disabled with a hint. `flagged` maps a chip's exact text to a reason: those chips get a danger style
 * and the reason as visible text under them.
 */
export function IngredientChipInput({
  label,
  values,
  onChange,
  max,
  flagged,
}: {
  label: string;
  values: string[];
  onChange: (v: string[]) => void;
  max: number;
  flagged?: Record<string, string>;
}) {
  const inputId = useId();
  const [text, setText] = useState('');
  const [query, setQuery] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);
  const refocusOnReopen = useRef(false); // set when a chip removal frees a slot at the cap

  // Follow the text after a pause in typing; clearing it is immediate.
  useEffect(() => {
    if (text.trim() === '') {
      setQuery('');
      return;
    }
    const timer = setTimeout(() => setQuery(text.trim()), SUGGEST_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [text]);

  const atMax = values.length >= max;
  const suggestions = useQuery({
    queryKey: ['ingredientSuggest', query],
    queryFn: () => suggestIngredients(query),
    enabled: query.length >= MIN_QUERY_LENGTH && !atMax,
    staleTime: 60_000,
  });

  const has = (name: string, list: string[]) => list.some((v) => v.toLowerCase() === name.toLowerCase());
  const shown =
    atMax || text.trim() === '' || !suggestions.isSuccess
      ? []
      : suggestions.data.filter((s) => !has(s, values)).slice(0, 8);

  // The input is disabled at the cap and can't take focus until it re-enables.
  useEffect(() => {
    if (!atMax && refocusOnReopen.current) {
      refocusOnReopen.current = false;
      inputRef.current?.focus();
    }
  }, [atMax]);

  /** Adds each name in order, skipping blanks and duplicates, up to the cap. */
  function add(names: string[]) {
    const next = [...values];
    for (const raw of names) {
      const name = clean(raw);
      if (name !== '' && next.length < max && !has(name, next)) next.push(name);
    }
    if (next.length !== values.length) onChange(next);
  }

  function onType(raw: string) {
    if (!raw.includes(',')) {
      setText(raw);
      return;
    }
    // Everything before the last comma is a finished name; the rest is still being typed.
    const parts = raw.split(',');
    const rest = parts.pop() ?? '';
    add(parts);
    setText(rest);
  }

  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key !== 'Enter') return;
    // Never let Enter submit the surrounding form from this field.
    e.preventDefault();
    add([text]);
    setText('');
  }

  function pick(name: string) {
    add([name]);
    setText('');
    inputRef.current?.focus();
  }

  function remove(name: string) {
    if (atMax) refocusOnReopen.current = true;
    onChange(values.filter((v) => v !== name));
  }

  return (
    <div>
      <label htmlFor={inputId} className="mb-2 block text-[15px] font-semibold text-ink">
        {label}
      </label>

      {values.length > 0 && (
        <ul className="mb-2.5 flex flex-wrap gap-2">
          {values.map((name) => {
            const reason = flagged && Object.hasOwn(flagged, name) ? flagged[name] : undefined;
            return (
              <li key={name} className="flex flex-col items-start gap-1">
                <span
                  className={`inline-flex min-h-10 items-center rounded-full pr-0 pl-4 text-[15px] font-medium ${
                    reason !== undefined
                      ? 'border border-danger bg-danger/10 text-danger'
                      : 'bg-ink text-on-ink'
                  }`}
                >
                  {name}
                  <button
                    type="button"
                    aria-label={`Remove ${name}`}
                    onClick={() => remove(name)}
                    className={`flex size-10 items-center justify-center rounded-full transition-colors ${
                      reason !== undefined ? 'hover:bg-danger/15' : 'hover:bg-on-ink/20'
                    }`}
                  >
                    <X aria-hidden="true" className="size-3.5" strokeWidth={2.5} />
                  </button>
                </span>
                {reason !== undefined && (
                  <span className="pl-4 text-[13px] font-medium text-danger">{name}: {reason}</span>
                )}
              </li>
            );
          })}
        </ul>
      )}

      <input
        id={inputId}
        ref={inputRef}
        type="text"
        autoComplete="off"
        disabled={atMax}
        placeholder="Type a name, then press Enter"
        value={text}
        onChange={(e) => onType(e.target.value)}
        onKeyDown={onKeyDown}
        className="w-full rounded-lg border border-field bg-surface px-3.5 py-2.5 text-base text-ink transition placeholder:text-ink-soft hover:border-ink-soft focus:border-ink focus:ring-4 focus:ring-ink/15 focus:outline-none disabled:cursor-not-allowed disabled:bg-tint disabled:opacity-70 sm:text-[15px]"
      />

      {shown.length > 0 && (
        <ul aria-label={`${label} suggestions`} className="mt-2 flex flex-wrap gap-2">
          {shown.map((s) => (
            <li key={s}>
              <button
                type="button"
                onClick={() => pick(s)}
                className="inline-flex min-h-10 items-center rounded-full border border-field bg-surface px-3.5 py-1 text-[15px] text-ink transition-colors hover:bg-tint"
              >
                {s}
              </button>
            </li>
          ))}
        </ul>
      )}

      {atMax && <p className="mt-1.5 text-[13px] text-ink-soft">Up to {max} ingredients</p>}
    </div>
  );
}
