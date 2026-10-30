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
 * disabled with a hint.
 */
export function IngredientChipInput({
  label,
  values,
  onChange,
  max,
}: {
  label: string;
  values: string[];
  onChange: (v: string[]) => void;
  max: number;
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
      <label htmlFor={inputId} className="mb-2 block text-[13px] font-semibold text-olive">
        {label}
      </label>

      {values.length > 0 && (
        <ul className="mb-2.5 flex flex-wrap gap-2">
          {values.map((name) => (
            <li
              key={name}
              className="inline-flex items-center gap-1 rounded-full bg-olive py-1 pr-1 pl-3.5 text-sm font-medium text-cream shadow-button"
            >
              {name}
              <button
                type="button"
                aria-label={`Remove ${name}`}
                onClick={() => remove(name)}
                className="flex size-6 items-center justify-center rounded-full transition hover:bg-cream/20 focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-saffron"
              >
                <X aria-hidden="true" className="size-3.5" strokeWidth={2.5} />
              </button>
            </li>
          ))}
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
        className="w-full rounded-xl border border-sand bg-paper px-3.5 py-2.5 text-sm text-olive shadow-[inset_0_1px_2px_rgb(47_58_43/0.04)] transition placeholder:text-olive/70 hover:border-olive/30 focus:border-olive/60 focus:ring-4 focus:ring-saffron/20 focus:outline-none disabled:cursor-not-allowed disabled:bg-cream disabled:opacity-70"
      />

      {shown.length > 0 && (
        <ul aria-label={`${label} suggestions`} className="mt-2 flex flex-wrap gap-2">
          {shown.map((s) => (
            <li key={s}>
              <button
                type="button"
                onClick={() => pick(s)}
                className="rounded-full border border-sand bg-paper px-3 py-1 text-sm text-olive transition hover:border-olive/40 hover:bg-cream focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-olive"
              >
                {s}
              </button>
            </li>
          ))}
        </ul>
      )}

      {atMax && <p className="mt-1.5 text-xs text-olive/70">Up to {max} ingredients</p>}
    </div>
  );
}
