import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import * as Select from '@radix-ui/react-select';
import { ChevronDown } from 'lucide-react';
import { SelectMenuContent, SelectMenuItem } from '@/components/SelectMenu';

const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

interface Parts {
  day: string;
  /** '1'..'12', or '' when no month is chosen. */
  month: string;
  year: string;
}

const EMPTY: Parts = { day: '', month: '', year: '' };

/** `YYYY-MM-DD` once every part is filled in (a 4-digit year, a day, a month), otherwise ''. */
function assemble({ day, month, year }: Parts): string {
  if (day === '' || month === '' || year.length !== 4) return '';
  return `${year}-${month.padStart(2, '0')}-${day.padStart(2, '0')}`;
}

function parse(value: string): Parts {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!m) return EMPTY;
  return { year: m[1], month: String(Number(m[2])), day: m[3] };
}

const digits = (raw: string, max: number) => raw.replace(/\D/g, '').slice(0, max);

/* One shared grid so the small labels sit exactly above their segments. */
const GRID = 'grid grid-cols-[4.25rem_minmax(0,1fr)_5.5rem]';
const SEGMENT =
  'min-w-0 bg-transparent px-3.5 py-2.5 text-sm leading-5 text-olive transition-colors focus:bg-cream focus:outline-none';

/**
 * A date of birth as three parts in one rounded control: Day, Month (a dropdown) and Year.
 * It only assembles a `YYYY-MM-DD` string (or '' while incomplete); validating it is the caller's job.
 */
export function BirthDateField({
  value,
  onChange,
  error,
}: {
  value: string;
  onChange: (value: string) => void;
  error?: string;
}) {
  const [parts, setParts] = useState<Parts>(() => parse(value));
  const groupLabelId = useId();
  const dayLabelId = useId();
  const monthLabelId = useId();
  const yearLabelId = useId();
  const errorId = useId();
  const dayRef = useRef<HTMLInputElement>(null);
  const monthRef = useRef<HTMLButtonElement>(null);
  const yearRef = useRef<HTMLInputElement>(null);
  // True when the open month menu was dismissed (Escape, outside click) rather than chosen from.
  const monthDismissed = useRef(false);

  // Follow the parent when it sets a different date from outside (e.g. a reset).
  useEffect(() => {
    if (value !== assemble(parts)) setParts(parse(value));
    // Only react to the parent's value; `parts` changes come from our own handlers.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  function update(next: Parts) {
    setParts(next);
    onChange(assemble(next));
  }

  function onDay(raw: string) {
    const day = digits(raw, 2);
    update({ ...parts, day });
    if (day.length === 2 || (day.length === 1 && Number(day) > 3)) monthRef.current?.focus();
  }

  function onYear(raw: string) {
    update({ ...parts, year: digits(raw, 4) });
  }

  function backspaceGoesBack(e: KeyboardEvent, isEmpty: boolean, previous: HTMLElement | null) {
    if (e.key !== 'Backspace' || !isEmpty) return;
    e.preventDefault();
    previous?.focus();
  }

  const invalid = error ? true : undefined;
  // The error is described on the group only, so a screen reader does not read it once per part.
  const describedBy = error ? errorId : undefined;

  return (
    <div role="group" aria-labelledby={groupLabelId} aria-describedby={describedBy}>
      <span id={groupLabelId} className="mb-1.5 block text-[13px] font-semibold text-olive">
        Date of birth
      </span>
      <div className={`${GRID} px-px text-xs font-medium text-olive/70`}>
        <span id={dayLabelId} className="mb-1 pl-3.5">Day</span>
        <span id={monthLabelId} className="mb-1 pl-3.5">Month</span>
        <span id={yearLabelId} className="mb-1 pl-3.5">Year</span>
      </div>
      <div
        className={`${GRID} divide-x divide-sand overflow-hidden rounded-xl border bg-paper shadow-[inset_0_1px_2px_rgb(47_58_43/0.04)] transition focus-within:ring-4 focus-within:ring-saffron/20 ${
          error ? 'border-danger' : 'border-sand focus-within:border-olive/60 hover:border-olive/30'
        }`}
      >
        <input
          ref={dayRef}
          type="text"
          inputMode="numeric"
          autoComplete="bday-day"
          placeholder="DD"
          aria-labelledby={dayLabelId}
          aria-invalid={invalid}
          value={parts.day}
          onChange={(e) => onDay(e.target.value)}
          className={`${SEGMENT} placeholder:text-olive/70`}
        />
        <Select.Root
          value={parts.month}
          onValueChange={(month) => update({ ...parts, month })}
          onOpenChange={(open) => {
            if (open) monthDismissed.current = false;
          }}
        >
          <Select.Trigger
            ref={monthRef}
            aria-labelledby={monthLabelId}
            aria-invalid={invalid}
            onKeyDown={(e) => backspaceGoesBack(e, true, dayRef.current)}
            className={`${SEGMENT} group flex items-center justify-between gap-1.5 text-left`}
          >
            <span className="truncate">
              <Select.Value placeholder={<span className="text-olive/70">Select</span>} />
            </span>
            <Select.Icon asChild>
              <ChevronDown
                aria-hidden="true"
                className="size-4 shrink-0 text-olive/70 transition-transform duration-200 group-data-[state=open]:rotate-180"
                strokeWidth={2.25}
              />
            </Select.Icon>
          </Select.Trigger>
          <SelectMenuContent
            onEscapeKeyDown={() => {
              monthDismissed.current = true;
            }}
            onPointerDownOutside={() => {
              monthDismissed.current = true;
            }}
            onCloseAutoFocus={(e) => {
              // Choosing a month (even the one already chosen) moves on to the year; dismissing
              // the menu leaves focus on the trigger.
              if (monthDismissed.current) return;
              e.preventDefault();
              yearRef.current?.focus();
            }}
          >
            {MONTHS.map((name, i) => (
              <SelectMenuItem key={name} value={String(i + 1)}>
                {name}
              </SelectMenuItem>
            ))}
          </SelectMenuContent>
        </Select.Root>
        <input
          ref={yearRef}
          type="text"
          inputMode="numeric"
          autoComplete="bday-year"
          placeholder="YYYY"
          aria-labelledby={yearLabelId}
          aria-invalid={invalid}
          value={parts.year}
          onChange={(e) => onYear(e.target.value)}
          onKeyDown={(e) => backspaceGoesBack(e, parts.year === '', monthRef.current)}
          className={`${SEGMENT} tabular-nums placeholder:text-olive/70`}
        />
      </div>
      {error && (
        <p id={errorId} role="alert" className="mt-1.5 text-xs font-medium text-danger">
          {error}
        </p>
      )}
    </div>
  );
}
