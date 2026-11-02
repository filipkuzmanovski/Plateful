import { useEffect, type ReactNode } from 'react';
import { useLocation } from 'react-router';
import { SITE } from '@/content/site';

export interface ContentSection {
  /** Anchor id, also used by the "On this page" index. */
  id: string;
  title: string;
  body: ReactNode;
}

/** decodeURIComponent throws on a malformed escape such as "%": fall back to the raw text. */
function safeDecode(value: string) {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

/** Index only when a page is long enough to need one. */
const INDEX_MIN_SECTIONS = 6;

/**
 * The shared layout for About, Privacy, Terms and Contact: a serif title on the left
 * (with an index on long documents) and prose capped at about 65 characters on the right.
 * Sets the tab title and starts at the top of the page.
 */
export function ContentPage({
  title,
  lede,
  showUpdated = false,
  sections,
  children,
}: {
  title: string;
  lede?: ReactNode;
  showUpdated?: boolean;
  sections?: ContentSection[];
  children?: ReactNode;
}) {
  const { hash } = useLocation();

  useEffect(() => {
    const previous = document.title;
    document.title = `${title} · ${SITE.name}`;
    return () => {
      document.title = previous;
    };
  }, [title]);

  // Start at the top, unless the link points at one section (an "On this page" link or a shared URL).
  useEffect(() => {
    const target = hash ? document.getElementById(safeDecode(hash.slice(1))) : null;
    if (target) target.scrollIntoView();
    else window.scrollTo(0, 0);
  }, [title, hash]);

  const hasIndex = (sections?.length ?? 0) >= INDEX_MIN_SECTIONS;

  return (
    <div className="grid gap-8 lg:grid-cols-[16rem_minmax(0,65ch)] lg:gap-16">
      <header className="lg:pt-1">
        <div className="lg:sticky lg:top-24 lg:flex lg:max-h-[calc(100vh-7rem)] lg:flex-col">
          <h1 className="font-serif text-4xl leading-[1.1] font-semibold tracking-[-0.015em] text-ink sm:text-5xl">
            {title}
          </h1>
          {showUpdated && (
            <p className="mt-3 text-[15px] text-ink-soft">Last updated: {SITE.legalLastUpdated}</p>
          )}
          {hasIndex && (
            <nav
              aria-label="On this page"
              className="mt-8 hidden border-t border-rule pt-5 lg:block lg:min-h-0 lg:overflow-y-auto"
            >
              <ul className="space-y-1.5 text-[15px]">
                {sections!.map((s) => (
                  <li key={s.id}>
                    <a
                      href={`#${s.id}`}
                      className="rounded-sm text-ink-soft underline decoration-transparent underline-offset-4 transition-colors hover:text-ink hover:decoration-ink"
                    >
                      {s.title}
                    </a>
                  </li>
                ))}
              </ul>
            </nav>
          )}
        </div>
      </header>
      <div className="min-w-0 max-w-prose space-y-5 text-base leading-relaxed text-ink">
        {lede && <p className="font-serif text-xl leading-relaxed text-ink sm:text-[22px]">{lede}</p>}
        {sections?.map((s) => (
          <section key={s.id} id={s.id} className="scroll-mt-20 space-y-3 pt-4">
            <h2 className="font-serif text-[28px] leading-tight font-medium tracking-[-0.015em] text-ink">
              {s.title}
            </h2>
            {s.body}
          </section>
        ))}
        {children}
      </div>
    </div>
  );
}

/** A link in the prose: ink text with a rule underline that darkens on hover. */
export const contentLinkClass =
  'font-semibold text-ink underline decoration-rule underline-offset-4 transition hover:decoration-ink';

/** A bulleted list in the prose column. */
export function Ul({ children }: { children: ReactNode }) {
  return <ul className="list-disc space-y-1.5 pl-5 marker:text-accent">{children}</ul>;
}

/** The contact address as a mailto link. */
export function MailLink({ className = '' }: { className?: string }) {
  return (
    <a
      href={`mailto:${SITE.contactEmail}`}
      className={`${contentLinkClass} ${className}`}
    >
      {SITE.contactEmail}
    </a>
  );
}

/** Inline emphasis for a defined term in the prose. */
export function Strong({ children }: { children: ReactNode }) {
  return <strong className="font-semibold text-ink">{children}</strong>;
}
