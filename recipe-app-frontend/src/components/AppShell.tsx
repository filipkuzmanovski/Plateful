import { Link, Navigate, NavLink, Outlet, useLocation, useNavigate } from 'react-router';
import { useEffect, useRef, useState } from 'react';
import { Toaster } from 'sonner';
import { useQuery } from '@tanstack/react-query';
import { ChevronDown, LogOut, Plus, UserRound } from 'lucide-react';
import { getPendingCount } from '@/api/admin';
import { useAuth } from '@/auth/AuthProvider';
import { IdleSessionGuard } from '@/auth/IdleSessionGuard';
import { OnboardingRedirect } from '@/auth/OnboardingRedirect';
import { ErrorBoundary } from './ErrorBoundary';
import SiteFooter from './SiteFooter';

const navLink = ({ isActive }: { isActive: boolean }) =>
  `relative rounded-full px-2.5 py-1.5 sm:px-3 transition-colors ${
    isActive ? 'bg-sand/60 text-olive' : 'text-olive/75 hover:bg-sand/40 hover:text-olive'
  }`;

const pillLink =
  'inline-flex items-center rounded-full px-4 py-1.5 font-semibold whitespace-nowrap transition duration-200';

/** The admin's way into the queue, with the number of recipes waiting once there are any. */
function ReviewQueueLink() {
  // No retries: an admin without two-factor gets a 403 here, and the link works fine without a badge.
  const { data: count = 0 } = useQuery({ queryKey: ['admin', 'pendingCount'], queryFn: getPendingCount, retry: false });
  return (
    <NavLink
      to="/admin/review"
      aria-label={count > 0 ? `Review queue, ${count} pending` : undefined}
      className={(s) => `${navLink(s)} inline-flex items-center gap-1.5`}
    >
      Review queue
      {count > 0 && (
        <span
          aria-hidden="true"
          className="rounded-full bg-saffron px-1.5 text-[11px] leading-[1.125rem] font-semibold text-olive tabular-nums"
        >
          {count}
        </span>
      )}
    </NavLink>
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
    <div className="flex min-h-screen flex-col">
      <header className="sticky top-0 z-30 border-b border-sand/80 bg-paper/85 backdrop-blur-md">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between gap-4 px-4 sm:px-6">
          <Link
            to="/"
            className="rounded-lg font-serif text-2xl leading-none sm:text-[1.65rem] font-bold tracking-tight text-olive"
          >
            Plateful<span className="text-saffron">.</span>
          </Link>
          <nav className="flex items-center gap-1 text-sm sm:gap-2">
            <NavLink to="/" end className={navLink}>Browse</NavLink>
            {status === 'ready' && <NavLink to="/cook" className={navLink}>Cook</NavLink>}
            {status === 'ready' && (
              <NavLink to="/recipes/new" className={(s) => `${navLink(s)} inline-flex items-center gap-1`}>
                <Plus aria-hidden="true" className="hidden size-4 sm:block" />
                New Recipe
              </NavLink>
            )}
            {status === 'ready' && profile?.isAdmin && <ReviewQueueLink />}
            {status === 'ready' && profile ? (
              <div ref={menuRef} className="relative ml-1">
                <button
                  onClick={() => setMenuOpen((v) => !v)}
                  aria-expanded={menuOpen}
                  className="inline-flex items-center gap-2 rounded-full border border-sand bg-paper py-1 pr-1.5 pl-1 font-semibold sm:pr-2.5 text-olive transition hover:border-olive/30 hover:shadow-card"
                >
                  <span
                    aria-hidden="true"
                    className="flex size-7 items-center justify-center rounded-full bg-olive font-serif text-[13px] text-cream"
                  >
                    {profile.firstName.charAt(0).toUpperCase()}
                  </span>
                  <span className="sr-only sm:not-sr-only">{profile.firstName}</span>
                  <ChevronDown
                    aria-hidden="true"
                    className={`size-4 text-olive/60 transition-transform ${menuOpen ? 'rotate-180' : ''}`}
                  />
                </button>
                {menuOpen && (
                  <div className="absolute right-0 z-40 mt-2 w-48 origin-top-right rounded-2xl border border-sand bg-paper p-1.5 text-left shadow-lift">
                    <Link
                      to="/me"
                      onClick={() => setMenuOpen(false)}
                      className="flex items-center gap-2.5 rounded-xl px-3 py-2 text-olive transition-colors hover:bg-cream"
                    >
                      <UserRound aria-hidden="true" className="size-4 text-olive/60" />
                      My profile
                    </Link>
                    <button
                      onClick={handleSignOut}
                      className="flex w-full items-center gap-2.5 rounded-xl px-3 py-2 text-left text-olive transition-colors hover:bg-cream"
                    >
                      <LogOut aria-hidden="true" className="size-4 text-olive/60" />
                      Sign out
                    </button>
                  </div>
                )}
              </div>
            ) : status === 'needs-profile' || status === 'needs-preferences' ? (
              <button
                onClick={handleSignOut}
                className={`${pillLink} ml-1 border border-olive/25 text-olive hover:border-olive/60 hover:bg-cream`}
              >
                Sign out
              </button>
            ) : status === 'needs-mfa' ? (
              <>
                <Link
                  to="/verify-2fa"
                  className={`${pillLink} ml-1 bg-olive text-cream shadow-button hover:-translate-y-px hover:bg-olive/90`}
                >
                  Finish signing in
                </Link>
                <button
                  onClick={handleSignOut}
                  className={`${pillLink} border border-olive/25 text-olive hover:border-olive/60 hover:bg-cream`}
                >
                  Sign out
                </button>
              </>
            ) : status !== 'loading' ? (
              <Link
                to="/login"
                className={`${pillLink} ml-1 bg-olive text-cream shadow-button hover:-translate-y-px hover:bg-olive/90`}
              >
                Sign in
              </Link>
            ) : null}
          </nav>
        </div>
      </header>
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-8 sm:px-6 sm:py-10">
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
            background: 'var(--color-paper)',
            color: 'var(--color-olive)',
            border: '1px solid var(--color-sand)',
            borderRadius: '1rem',
            fontFamily: 'var(--font-sans)',
          },
        }}
      />
    </div>
  );
}
