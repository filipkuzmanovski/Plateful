import { Navigate, useLocation } from 'react-router';
import type { ReactNode } from 'react';
import { useAuth } from './AuthProvider';
import { Spinner } from '@/components/ui';

export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const location = useLocation();

  if (status === 'loading') return <Spinner />;
  if (status === 'anonymous') return <Navigate to="/login" state={{ from: location.pathname + location.search }} replace />;
  if (status === 'needs-profile' && location.pathname !== '/complete-profile')
    return <Navigate to="/complete-profile" replace />;
  if (status === 'needs-mfa' && location.pathname !== '/verify-2fa')
    return <Navigate to="/verify-2fa" state={{ from: location.pathname + location.search }} replace />;
  return <>{children}</>;
}
