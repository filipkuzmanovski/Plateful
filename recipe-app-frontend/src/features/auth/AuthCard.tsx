import type { ReactNode } from 'react';
import { ChefHat } from 'lucide-react';

/** A centered paper card on the cream page, with a serif heading: the frame for sign-in style forms. */
export function AuthCard({
  title,
  subtitle,
  children,
  footer,
}: {
  title: string;
  subtitle?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
}) {
  return (
    <div className="flex justify-center py-4 sm:py-12">
      <div className="w-full max-w-md">
        <div className="rounded-card bg-paper p-6 shadow-lift ring-1 ring-sand/70 sm:p-9">
          <span
            aria-hidden="true"
            className="mb-5 flex size-12 items-center justify-center rounded-full bg-terracotta/10 text-terracotta"
          >
            <ChefHat className="size-6" strokeWidth={1.5} />
          </span>
          <h1 className="font-serif text-4xl leading-tight font-semibold tracking-tight text-olive">{title}</h1>
          {subtitle && <p className="mt-2 text-sm leading-relaxed text-olive/70">{subtitle}</p>}
          <div className="mt-7">{children}</div>
        </div>
        {footer && <p className="mt-5 text-center text-sm text-olive/70">{footer}</p>}
      </div>
    </div>
  );
}

/** The inline error line under an auth form. */
export function FormError({ children }: { children: ReactNode }) {
  return (
    <p role="alert" className="rounded-xl bg-danger/8 px-3.5 py-2.5 text-sm font-medium text-danger">
      {children}
    </p>
  );
}

export const authLinkClass =
  'font-semibold text-olive underline decoration-olive/30 underline-offset-4 transition hover:decoration-saffron';
