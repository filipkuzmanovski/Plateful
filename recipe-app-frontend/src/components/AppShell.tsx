import { Link, Navigate, Outlet, useLocation, useNavigate } from 'react-router';
import { useState } from 'react';
import { Toaster } from 'sonner';
import { useAuth } from '@/auth/AuthProvider';
import { ErrorBoundary } from './ErrorBoundary';

export default function AppShell() {
  const { status, profile, signOut } = useAuth();
  const [menuOpen, setMenuOpen] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const handleSignOut = async () => { setMenuOpen(false); await signOut(); navigate('/'); };

  return (
    <div className="mx-auto max-w-6xl px-4">
      <header className="flex items-center justify-between border-b-2 border-ink py-4">
        <Link to="/" className="text-2xl font-extrabold tracking-tighter">PLATEFUL</Link>
        <nav className="flex items-center gap-5 text-sm">
          <Link to="/" className="hover:text-accent">Browse</Link>
          {status === 'ready' && (
            <Link to="/recipes/new" className="hover:text-accent">New Recipe</Link>
          )}
          {status === 'ready' && profile ? (
            <div className="relative">
              <button
                onClick={() => setMenuOpen((v) => !v)}
                className="font-bold uppercase tracking-wide hover:text-accent"
              >
                {profile.firstName}
              </button>
              {menuOpen && (
                <div className="absolute right-0 z-10 mt-2 w-40 border border-ink bg-white text-left">
                  <Link to="/me" onClick={() => setMenuOpen(false)} className="block px-3 py-2 hover:bg-ink hover:text-white">
                    My profile
                  </Link>
                  <button
                    onClick={handleSignOut}
                    className="block w-full px-3 py-2 text-left hover:bg-ink hover:text-white"
                  >
                    Sign out
                  </button>
                </div>
              )}
            </div>
          ) : status === 'needs-profile' ? (
            <button
              onClick={handleSignOut}
              className="border-2 border-ink px-3 py-1 font-bold uppercase tracking-wide hover:bg-ink hover:text-white"
            >
              Sign out
            </button>
          ) : status !== 'loading' ? (
            <Link to="/login" className="border-2 border-ink px-3 py-1 font-bold uppercase tracking-wide hover:bg-ink hover:text-white">
              Sign in
            </Link>
          ) : null}
        </nav>
      </header>
      <main className="py-8">
        <ErrorBoundary key={location.pathname}>
          {status === 'needs-profile' && location.pathname !== '/complete-profile' ? (
            <Navigate to="/complete-profile" replace />
          ) : (
            <Outlet />
          )}
        </ErrorBoundary>
      </main>
      <Toaster position="bottom-right" />
    </div>
  );
}
