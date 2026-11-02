import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Search, X } from 'lucide-react';

const SEARCH_DEBOUNCE_MS = 300;

/**
 * The recipe search box. The text is local state, pushed to the URL 300 ms after the last keystroke
 * (Enter and Clear push immediately). When `value` (the `q` in the URL) changes from elsewhere, such as
 * Back or "Clear filters", the box follows it.
 */
export function SearchBar({ value, onSearch }: { value: string; onSearch: (q: string) => void }) {
  const [text, setText] = useState(value);
  const inputRef = useRef<HTMLInputElement>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  // The last value this box pushed to the URL (or read from it), so our own writes never echo back
  // into the box while the user is still typing.
  const synced = useRef(value);
  const onSearchRef = useRef(onSearch);
  onSearchRef.current = onSearch;

  useEffect(() => {
    if (value === synced.current) return;
    synced.current = value;
    clearTimeout(timer.current);
    setText(value);
  }, [value]);

  useEffect(() => () => clearTimeout(timer.current), []);

  const commit = (next: string) => {
    clearTimeout(timer.current);
    const trimmed = next.trim();
    if (trimmed === synced.current) return;
    synced.current = trimmed;
    onSearchRef.current(trimmed);
  };

  const change = (next: string) => {
    setText(next);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => commit(next), SEARCH_DEBOUNCE_MS);
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    commit(text);
  };

  return (
    <form role="search" onSubmit={submit} className="relative mt-6 w-full sm:max-w-lg">
      <Search
        aria-hidden="true"
        className="pointer-events-none absolute top-1/2 left-4 size-4 -translate-y-1/2 text-ink-soft"
        strokeWidth={2.25}
      />
      <input
        ref={inputRef}
        type="search"
        aria-label="Search recipes"
        placeholder="Search recipes or ingredients"
        value={text}
        onChange={(e) => change(e.target.value)}
        autoComplete="off"
        className="h-12 w-full rounded-full border border-field bg-surface pr-12 pl-11 text-base text-ink transition placeholder:text-ink-soft hover:border-ink-soft focus:border-ink focus:ring-4 focus:ring-ink/15 focus:outline-none sm:text-[15px] [&::-webkit-search-cancel-button]:hidden [&::-webkit-search-cancel-button]:appearance-none"
      />
      {text !== '' && (
        <button
          type="button"
          aria-label="Clear search"
          onClick={() => {
            setText('');
            commit('');
            inputRef.current?.focus();
          }}
          className="absolute top-1/2 right-1.5 flex size-10 -translate-y-1/2 items-center justify-center rounded-full text-ink-soft transition hover:bg-tint hover:text-ink"
        >
          <X aria-hidden="true" className="size-4" strokeWidth={2.25} />
        </button>
      )}
    </form>
  );
}
