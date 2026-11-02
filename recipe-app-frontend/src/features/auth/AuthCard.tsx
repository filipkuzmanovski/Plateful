import type { ReactNode } from 'react';

/** A centered surface panel on the page, with a serif heading: the frame for sign-in style forms. */
export function AuthCard({
  title,
  subtitle,
  children,
  footer,
  wide = false,
}: {
  title: string;
  subtitle?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  /** Roomier card for forms with chip groups. */
  wide?: boolean;
}) {
  return (
    <div className="flex justify-center py-4 sm:py-12">
      <div className={`w-full ${wide ? 'max-w-2xl' : 'max-w-md'}`}>
        <div className="rounded-card border border-rule bg-surface p-6 sm:p-9">
          <p aria-hidden="true" className="mb-4 font-serif text-xl leading-none font-semibold tracking-[-0.015em] text-ink">
            Plateful<span className="text-accent">.</span>
          </p>
          <h1 className="font-serif text-4xl leading-tight font-semibold tracking-[-0.015em] text-ink">{title}</h1>
          {subtitle && <p className="mt-2 text-[15px] leading-relaxed text-ink-soft">{subtitle}</p>}
          <div className="mt-7">{children}</div>
        </div>
        {footer && <p className="mt-5 text-center text-[15px] text-ink-soft">{footer}</p>}
      </div>
    </div>
  );
}

/** The inline error line under an auth form. */
export function FormError({ children }: { children: ReactNode }) {
  return (
    <p role="alert" className="rounded-lg bg-danger/10 px-3.5 py-2.5 text-[15px] font-medium text-danger">
      {children}
    </p>
  );
}

export const authLinkClass =
  'font-semibold text-ink underline decoration-rule underline-offset-4 transition hover:decoration-ink';
