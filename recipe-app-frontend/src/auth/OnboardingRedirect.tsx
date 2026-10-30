import { Navigate, useLocation } from 'react-router';
import { useAuth } from './AuthProvider';

/** Pages a user who has not set preferences yet may still read. */
const OPEN_PATHS = new Set(['/preferences/setup', '/privacy', '/terms', '/about', '/contact']);

/** Sends a signed-in user without saved food preferences to the setup page from any other page. */
export function OnboardingRedirect() {
  const { status } = useAuth();
  const { pathname } = useLocation();
  if (status !== 'needs-preferences' || OPEN_PATHS.has(pathname)) return null;
  return <Navigate to="/preferences/setup" replace />;
}
