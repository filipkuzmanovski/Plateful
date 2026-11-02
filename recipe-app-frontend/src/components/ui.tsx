import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode } from 'react';
import { LoaderCircle, type LucideIcon } from 'lucide-react';

type ButtonVariant = 'primary' | 'outline' | 'danger' | 'ghost';

const buttonVariants: Record<ButtonVariant, string> = {
  primary: 'bg-ink text-on-ink hover:bg-ink/90',
  outline: 'border border-field bg-surface text-ink hover:bg-tint',
  danger: 'border border-danger/50 bg-surface text-danger hover:bg-danger hover:text-on-danger',
  ghost: 'text-ink hover:bg-tint',
};

export function Button({
  variant = 'primary',
  className = '',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant }) {
  return (
    <button
      className={`inline-flex items-center justify-center gap-2 min-h-11 rounded-full px-5 py-2 text-[15px] font-semibold whitespace-nowrap transition-colors duration-200 ease-out disabled:pointer-events-none disabled:opacity-45 ${buttonVariants[variant]} ${className}`}
      {...props}
    />
  );
}

export function Field({
  label,
  error,
  children,
}: {
  label: string;
  error?: string;
  children: ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-[15px] font-semibold text-ink">{label}</span>
      {children}
      {error && <span role="alert" className="mt-1.5 block text-[13px] font-medium text-danger">{error}</span>}
    </label>
  );
}

export function TextInput(props: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      className="w-full rounded-lg border border-field bg-surface px-3.5 py-2.5 text-base text-ink transition placeholder:text-ink-soft hover:border-ink-soft focus:border-ink focus:ring-4 focus:ring-ink/15 focus:outline-none sm:text-[15px]"
      {...props}
    />
  );
}

export function SectionHeading({ children }: { children: ReactNode }) {
  return (
    <h2 className="mt-10 mb-4 border-b border-ink pb-1.5 font-serif text-[28px] font-medium tracking-[-0.015em] text-ink">
      {children}
    </h2>
  );
}

export function Spinner() {
  return (
    <p role="status" className="flex items-center justify-center gap-2 py-16 text-[15px] text-ink-soft">
      <LoaderCircle aria-hidden="true" className="size-4 animate-spin text-ink-soft" />
      Loading…
    </p>
  );
}

const chipTones = {
  ink: {
    on: 'border-ink bg-ink text-on-ink',
    off: 'border-field bg-surface text-ink hover:bg-tint',
    badgeOn: 'bg-on-ink/15 text-on-ink',
    badgeOff: 'bg-tint text-ink-soft',
  },
  /* Regions: a saffron wash when off, solid saffron when on. */
  accent: {
    on: 'border-accent bg-accent font-semibold text-on-accent',
    off: 'border-transparent bg-accent/15 font-semibold text-accent-ink hover:bg-accent/25',
    badgeOn: 'bg-on-accent/15 text-on-accent',
    badgeOff: 'bg-accent/15 text-accent-ink',
  },
} as const;

/** A toggleable filter chip (region, cuisine). `aria-pressed` reflects `selected`. */
export function Chip({
  selected,
  onClick,
  children,
  count,
  tone = 'ink',
  className = '',
  ...props
}: Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'onClick' | 'children'> & {
  selected: boolean;
  onClick: () => void;
  children: ReactNode;
  count?: number;
  tone?: keyof typeof chipTones;
}) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onClick}
      className={`inline-flex min-h-10 items-center gap-1.5 rounded-full border px-3.5 py-1.5 text-[15px] font-medium transition-colors duration-150 ${
        chipTones[tone][selected ? 'on' : 'off']
      } ${className}`}
      {...props}
    >
      {children}
      {count !== undefined && (
        <span
          className={`rounded-full px-1.5 text-[12px] leading-[1.125rem] font-bold tabular-nums ${
            chipTones[tone][selected ? 'badgeOn' : 'badgeOff']
          }`}
        >
          {count}
        </span>
      )}
    </button>
  );
}

const pillTones = {
  tint: 'bg-tint text-ink',
  accent: 'bg-accent/15 text-accent-ink',
  ink: 'bg-ink text-on-ink',
  danger: 'bg-danger/10 text-danger',
} as const;

/** A small, non-interactive label (cuisine tag, macro, badge). */
export function Pill({
  children,
  tone = 'tint',
  className = '',
}: {
  children: ReactNode;
  tone?: keyof typeof pillTones;
  className?: string;
}) {
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-sm px-2 py-0.5 text-[13px] font-medium ${pillTones[tone]} ${className}`}
    >
      {children}
    </span>
  );
}

/** A centered, quiet message for empty and error states: a small icon above a serif sentence. */
export function EmptyState({
  icon: Icon,
  children,
  action,
}: {
  icon: LucideIcon;
  children: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center gap-3 px-4 py-16 text-center">
      <Icon aria-hidden="true" className="size-6 text-ink-soft" strokeWidth={1.5} />
      <p className="max-w-sm font-serif text-[22px] leading-snug text-ink">{children}</p>
      {action}
    </div>
  );
}
