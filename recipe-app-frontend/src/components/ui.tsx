import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode } from 'react';
import { LoaderCircle } from 'lucide-react';

type ButtonVariant = 'primary' | 'outline' | 'danger' | 'ghost';

const buttonVariants: Record<ButtonVariant, string> = {
  primary: 'bg-olive text-cream shadow-button hover:-translate-y-px hover:bg-olive/90 hover:shadow-lift',
  outline: 'border border-olive/20 bg-paper text-olive hover:border-olive/50 hover:bg-cream',
  danger: 'border border-danger/35 bg-paper text-danger hover:border-danger hover:bg-danger hover:text-paper',
  ghost: 'text-olive hover:bg-sand/60',
};

export function Button({
  variant = 'primary',
  className = '',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant }) {
  return (
    <button
      className={`inline-flex items-center justify-center gap-2 rounded-full px-5 py-2.5 text-sm font-semibold whitespace-nowrap transition duration-200 ease-out active:translate-y-0 disabled:pointer-events-none disabled:opacity-45 ${buttonVariants[variant]} ${className}`}
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
      <span className="mb-1.5 block text-[13px] font-semibold text-olive">{label}</span>
      {children}
      {error && <span role="alert" className="mt-1.5 block text-xs font-medium text-danger">{error}</span>}
    </label>
  );
}

export function TextInput(props: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      className="w-full rounded-xl border border-sand bg-paper px-3.5 py-2.5 text-sm text-olive shadow-[inset_0_1px_2px_rgb(47_58_43/0.04)] transition placeholder:text-olive/70 hover:border-olive/30 focus:border-saffron focus:ring-4 focus:ring-saffron/20 focus:outline-none"
      {...props}
    />
  );
}

export function SectionHeading({ children }: { children: ReactNode }) {
  return (
    <h2 className="mt-10 mb-4 flex items-center gap-4 font-serif text-2xl font-semibold tracking-tight text-olive after:h-px after:flex-1 after:bg-sand">
      {children}
    </h2>
  );
}

export function Spinner() {
  return (
    <p role="status" className="flex items-center justify-center gap-2 py-16 text-sm text-olive/70">
      <LoaderCircle aria-hidden="true" className="size-4 animate-spin text-saffron" />
      Loading…
    </p>
  );
}

/** A toggleable filter chip (region, cuisine). `aria-pressed` reflects `selected`. */
export function Chip({
  selected,
  onClick,
  children,
  count,
  className = '',
  ...props
}: Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'onClick' | 'children'> & {
  selected: boolean;
  onClick: () => void;
  children: ReactNode;
  count?: number;
}) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onClick}
      className={`inline-flex items-center gap-1.5 rounded-full border px-3.5 py-1.5 text-sm font-medium transition duration-150 ${
        selected
          ? 'border-olive bg-olive text-cream shadow-button'
          : 'border-sand bg-paper text-olive hover:border-olive/40 hover:bg-cream'
      } ${className}`}
      {...props}
    >
      {children}
      {count !== undefined && (
        <span
          className={`rounded-full px-1.5 text-[11px] leading-[1.125rem] font-semibold tabular-nums ${
            selected ? 'bg-cream/15 text-cream' : 'bg-sand/70 text-olive/75'
          }`}
        >
          {count}
        </span>
      )}
    </button>
  );
}

const pillTones = {
  sand: 'bg-sand/70 text-olive',
  saffron: 'bg-saffron/15 text-[#7a4f16]',
  olive: 'bg-olive/10 text-olive',
  terracotta: 'bg-terracotta/10 text-[#9c4329]',
} as const;

/** A small, non-interactive label (cuisine tag, macro, badge). */
export function Pill({
  children,
  tone = 'sand',
  className = '',
}: {
  children: ReactNode;
  tone?: keyof typeof pillTones;
  className?: string;
}) {
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium ${pillTones[tone]} ${className}`}
    >
      {children}
    </span>
  );
}
