import type { ReactNode } from 'react';
import { SearchX } from 'lucide-react';
import { ProtectedRoute } from '@/auth/ProtectedRoute';
import { useAuth } from '@/auth/AuthProvider';
import { EmptyState } from '@/components/ui';

function AdminOnly({ children }: { children: ReactNode }) {
  const { profile } = useAuth();
  // A non-admin gets exactly what an unknown address gets, so the admin pages are not advertised.
  if (!profile?.isAdmin) return <EmptyState icon={SearchX}>Page not found.</EmptyState>;
  return <>{children}</>;
}

/** Signed-out visitors are sent to sign in, as on any protected page; signed-in non-admins see "Page not found." */
export function AdminRoute({ children }: { children: ReactNode }) {
  return (
    <ProtectedRoute>
      <AdminOnly>{children}</AdminOnly>
    </ProtectedRoute>
  );
}
