import { Link, Navigate, NavLink, Outlet, useLocation, useNavigate } from 'react-router';
import { useEffect, useRef, useState } from 'react';
import { Toaster } from 'sonner';
import { useQuery } from '@tanstack/react-query';
import { ChevronDown, ClipboardCheck, LogOut, Plus, UserRound } from 'lucide-react';
import { getReportCount } from '@/api/admin';
import { useAuth } from '@/auth/AuthProvider';
import { IdleSessionGuard } from '@/auth/IdleSessionGuard';
import { OnboardingRedirect } from '@/auth/OnboardingRedirect';
import { ErrorBoundary } from './ErrorBoundary';
import SiteFooter from './SiteFooter';
import ThemeMenu from './ThemeMenu';
import { ThemeProvider } from '@/theme/ThemeProvider';

// The display class is not part of the base: a bare `inline-flex` would beat `hidden` (same specificity,
// later in the generated CSS) and keep wide-only links on phones.
const navBase = ({ isActive }: { isActive: boolean }) =>
  `min-h-10 items-center border-b-2 px-2.5 transition-colors sm:px-3 ${
    isActive ? 'border-ink text-ink' : 'border-transparent text-ink-soft hover:text-ink'
  }`;

const navLink = (state: { isActive: boolean }) => `inline-flex ${navBase(state)}`;

/** Links that only fit once there is room; below `md` they live in the user menu. */
const wideOnly = (state: { isActive: boolean }) => `hidden md:inline-flex ${navBase(state)}`;

const pillLink =
  'inline-flex min-h-11 items-center rounded-full px-4 font-semibold whitespace-nowrap transition-colors';

const menuItem =
  'flex min-h-11 w-full items-center gap-2.5 rounded-lg px-3 py-2 text-left text-[15px] text-ink transition-colors hover:bg-tint';

/** The number of reported items waiting for an admin. No retries: an admin without two-factor gets a 403 here, and the link works fine without a badge. */
function useReportCount() {
  const { data: count = 0 } = useQuery({ queryKey: ['admin', 'reportCount'], queryFn: getReportCount, retry: false });
  return count;
}

function CountBadge({ count }: { count: number }) {
  return (
    <span
      aria-hidden="true"
      className="rounded-full bg-accent px-1.5 text-[12px] leading-[1.125rem] font-bold text-on-accent tabular-nums"
    >
      {count}
    </span>
  );
}

/** The admin's way into the reports queue, with the number of open items once there are any. */
function ReportsLink() {
  const count = useReportCount();
  return (
    <NavLink
      to="/admin/reports"
      aria-label={count > 0 ? `Reports, ${count} open` : undefined}
      className={(s) => `${wideOnly(s)} gap-1.5`}
    >
      Reports
      {count > 0 && <CountBadge count={count} />}
    </NavLink>
  );
}

/** The same link inside the user menu, for phones. */
function ReportsMenuItem({ onNavigate }: { onNavigate: () => void }) {
  const count = useReportCount();
  return (
    <Link
      to="/admin/reports"
      onClick={onNavigate}
      aria-label={count > 0 ? `Reports, ${count} open` : undefined}
      className={`${menuItem} md:hidden`}
    >
      <ClipboardCheck aria-hidden="true" className="size-4 text-ink-soft" />
      <span className="flex-1">Reports</span>
      {count > 0 && <CountBadge count={count} />}
    </Link>
  );
}

export default function AppShell() {
  const { status, profile, signOut } = useAuth();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const location = useLocation();
  const handleSignOut = async () => { setMenuOpen(false); await signOut(); navigate('/'); };

  // Close the user menu on an outside click or Escape.
  useEffect(() => {
    if (!menuOpen) return;
    const onPointer = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenuOpen(false);
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setMenuOpen(false); };
    document.addEventListener('mousedown', onPointer);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onPointer);
      document.removeEventListener('keydown', onKey);
    };
  }, [menuOpen]);

  return (
    <ThemeProvider>
      <div className="flex min-h-screen flex-col">
        <header className="sticky top-0 z-30 border-b border-rule bg-page/95 print:hidden">
          <div className="mx-auto flex h-16 max-w-6xl items-center justify-between gap-4 px-4 sm:px-6">
            <Link
              to="/"
              className="rounded-lg font-serif text-2xl leading-none font-semibold tracking-tight text-ink sm:text-[1.65rem]"
            >
              Plateful<span className="text-accent">.</span>
            </Link>
            <nav className="flex items-center gap-1 text-[15px] sm:gap-2">
              {/* Signed in (or half signed in), Browse is the wordmark's job on phones; signed out it stays. */}
              <NavLink to="/" end className={status === 'ready' || status === 'needs-mfa' ? wideOnly : navLink}>Browse</NavLink>
              {status === 'ready' && <NavLink to="/cook" className={navLink}>Cook</NavLink>}
              {status === 'ready' && (
                <NavLink to="/recipes/new" className={(s) => `${wideOnly(s)} gap-1`}>
                  <Plus aria-hidden="true" className="size-4" />
                  New recipe
                </NavLink>
              )}
              {status === 'ready' && profile?.isAdmin && <ReportsLink />}
              <ThemeMenu />
              {status === 'ready' && profile ? (
                <div ref={menuRef} className="relative ml-1">
                  <button
                    onClick={() => setMenuOpen((v) => !v)}
                    aria-expanded={menuOpen}
                    className="inline-flex min-h-10 items-center gap-2 rounded-full border border-rule bg-surface py-1 pr-1.5 pl-1 font-semibold text-ink transition-colors hover:border-ink-soft sm:pr-2.5"
                  >
                    <span
                      aria-hidden="true"
                      className="flex size-7 items-center justify-center rounded-full bg-ink font-serif text-[13px] text-on-ink"
                    >
                      {profile.firstName.charAt(0).toUpperCase()}
                    </span>
                    <span className="sr-only sm:not-sr-only">{profile.firstName}</span>
                    <ChevronDown
                      aria-hidden="true"
                      className={`size-4 text-ink-soft transition-transform ${menuOpen ? 'rotate-180' : ''}`}
                    />
                  </button>
                  {menuOpen && (
                    <div
                      data-testid="user-menu"
                      className="absolute right-0 z-40 mt-2 w-56 origin-top-right rounded-card border border-rule bg-surface p-1.5 text-left shadow-float"
                    >
                      <Link
                        to="/recipes/new"
                        onClick={() => setMenuOpen(false)}
                        className={`${menuItem} md:hidden`}
                      >
                        <Plus aria-hidden="true" className="size-4 text-ink-soft" />
                        New recipe
                      </Link>
                      {profile.isAdmin && <ReportsMenuItem onNavigate={() => setMenuOpen(false)} />}
                      <Link to="/me" onClick={() => setMenuOpen(false)} className={menuItem}>
                        <UserRound aria-hidden="true" className="size-4 text-ink-soft" />
                        My profile
                      </Link>
                      <button onClick={handleSignOut} className={menuItem}>
                        <LogOut aria-hidden="true" className="size-4 text-ink-soft" />
                        Sign out
                      </button>
                    </div>
                  )}
                </div>
              ) : status === 'needs-profile' || status === 'needs-preferences' ? (
                <button
                  onClick={handleSignOut}
                  className={`${pillLink} ml-1 border border-field bg-surface text-ink hover:bg-tint`}
                >
                  Sign out
                </button>
              ) : status === 'needs-mfa' ? (
                <>
                  {/* Already on the verify page, the link would only point at itself. */}
                  {location.pathname !== '/verify-2fa' && (
                    <Link
                      to="/verify-2fa"
                      className={`${pillLink} ml-1 bg-ink text-on-ink hover:bg-ink/90`}
                    >
                      Finish signing in
                    </Link>
                  )}
                  <button
                    onClick={handleSignOut}
                    className={`${pillLink} border border-field bg-surface text-ink hover:bg-tint`}
                  >
                    Sign out
                  </button>
                </>
              ) : status !== 'loading' ? (
                <Link
                  to="/login"
                  className={`${pillLink} ml-1 bg-ink text-on-ink hover:bg-ink/90`}
                >
                  Sign in
                </Link>
              ) : null}
            </nav>
          </div>
        </header>
        <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-8 sm:px-6 sm:py-10 print:max-w-none print:p-0">
          <OnboardingRedirect />
          <ErrorBoundary key={location.pathname}>
            {status === 'needs-profile' && location.pathname !== '/complete-profile' ? (
              <Navigate to="/complete-profile" replace />
            ) : (
              <Outlet />
            )}
          </ErrorBoundary>
        </main>
        <SiteFooter />
        <IdleSessionGuard />
        <Toaster
          position="bottom-right"
          toastOptions={{
            style: {
              background: 'var(--color-surface)',
              color: 'var(--color-ink)',
              border: '1px solid var(--color-rule)',
              borderRadius: 'var(--radius-card)',
              boxShadow: 'var(--shadow-float)',
              fontFamily: 'var(--font-sans)',
            },
          }}
        />
      </div>
    </ThemeProvider>
  );
}
