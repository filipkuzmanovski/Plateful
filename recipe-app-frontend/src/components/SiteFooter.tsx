import { Link } from 'react-router';
import { SITE } from '@/content/site';

const linkClass =
  'inline-flex min-h-8 items-center rounded-sm text-ink-soft underline decoration-transparent decoration-1 underline-offset-4 transition-colors hover:text-ink hover:decoration-ink';

function FooterColumn({ label, links }: { label: string; links: { to: string; text: string }[] }) {
  return (
    <nav aria-label={label}>
      <p aria-hidden="true" className="font-serif text-[18px] font-semibold tracking-tight text-ink">
        {label}
      </p>
      <ul className="mt-3 space-y-1 text-[15px]">
        {links.map((l) => (
          <li key={l.to}>
            <Link to={l.to} className={linkClass}>
              {l.text}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}

/** The site footer: wordmark and tagline, Explore and Legal links, and the credit line. */
export default function SiteFooter() {
  return (
    <footer className="print:hidden mt-8 border-t border-rule bg-tint">
      <div className="mx-auto max-w-6xl px-4 pt-12 pb-8 sm:px-6">
        <div className="grid gap-10 sm:grid-cols-2 lg:grid-cols-[minmax(0,1.6fr)_1fr_1fr] lg:gap-16">
          <div>
            <Link
              to="/"
              className="rounded-lg font-serif text-2xl leading-none font-semibold tracking-tight text-ink"
            >
              {SITE.name}
              <span className="text-accent">.</span>
            </Link>
            <p className="mt-3 max-w-xs text-[15px] leading-relaxed text-ink-soft">
              Recipes from every coast, shared by home cooks.
            </p>
          </div>
          <FooterColumn
            label="Explore"
            links={[
              { to: '/', text: 'Browse' },
              { to: '/about', text: 'About us' },
              { to: '/contact', text: 'Contact' },
            ]}
          />
          <FooterColumn
            label="Legal"
            links={[
              { to: '/privacy', text: 'Privacy Policy' },
              { to: '/terms', text: 'Terms of Service' },
            ]}
          />
        </div>
        <p className="mt-10 border-t border-rule pt-5 text-[13px] leading-relaxed text-ink-soft">
          © {SITE.copyrightYear} {SITE.name}. A student portfolio project. Some recipe data provided by{' '}
          <a
            href="https://spoonacular.com/food-api"
            rel="noopener noreferrer"
            className="rounded-sm underline decoration-ink-soft underline-offset-2 hover:text-ink hover:decoration-ink"
          >
            Spoonacular
          </a>
          .
        </p>
      </div>
    </footer>
  );
}
