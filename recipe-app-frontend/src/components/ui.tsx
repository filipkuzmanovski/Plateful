import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode } from 'react';

export function Button({
  variant = 'primary',
  className = '',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'outline' | 'danger' }) {
  const styles = {
    primary: 'bg-ink text-white hover:bg-accent',
    outline: 'border border-ink text-ink hover:bg-ink hover:text-white',
    danger: 'border border-red-600 text-red-600 hover:bg-red-600 hover:text-white',
  }[variant];
  return (
    <button
      className={`px-4 py-2 text-sm font-bold tracking-wide uppercase transition-colors disabled:opacity-40 ${styles} ${className}`}
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
      <span className="mb-1 block text-xs font-bold tracking-widest text-ink uppercase">{label}</span>
      {children}
      {error && <span role="alert" className="mt-1 block text-xs text-red-600">{error}</span>}
    </label>
  );
}

export function TextInput(props: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      className="w-full border border-hairline px-3 py-2 text-sm focus:border-ink focus:outline-none"
      {...props}
    />
  );
}

export function SectionHeading({ children }: { children: ReactNode }) {
  return (
    <h2 className="mt-8 mb-3 border-b-2 border-ink pb-1 text-sm font-bold tracking-widest uppercase">
      {children}
    </h2>
  );
}

export function Spinner() {
  return <p role="status" className="py-12 text-center text-sm text-muted">Loading…</p>;
}
