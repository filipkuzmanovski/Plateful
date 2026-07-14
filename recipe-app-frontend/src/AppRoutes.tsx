import { Route, Routes } from 'react-router';
import AppShell from '@/components/AppShell';
import { ProtectedRoute } from '@/auth/ProtectedRoute';
import LoginPage from '@/features/auth/LoginPage';
import SignupPage from '@/features/auth/SignupPage';
import CompleteProfilePage from '@/features/profile/CompleteProfilePage';
import BrowsePage from '@/features/browse/BrowsePage';
import RecipeDetailPage from '@/features/recipe/RecipeDetailPage';
import RecipeFormPage from '@/features/authoring/RecipeFormPage';
import MyProfilePage from '@/features/profile/MyProfilePage';
import PublicProfilePage from '@/features/profile/PublicProfilePage';

export default function AppRoutes() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/" element={<BrowsePage />} />
        <Route path="/recipes/new" element={<ProtectedRoute><RecipeFormPage /></ProtectedRoute>} />
        <Route path="/recipes/:id" element={<RecipeDetailPage />} />
        <Route path="/recipes/:id/edit" element={<ProtectedRoute><RecipeFormPage /></ProtectedRoute>} />
        <Route path="/users/:id" element={<PublicProfilePage />} />
        <Route path="/me" element={<ProtectedRoute><MyProfilePage /></ProtectedRoute>} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/complete-profile" element={<ProtectedRoute><CompleteProfilePage /></ProtectedRoute>} />
        <Route path="*" element={<p className="py-12 text-center text-muted">Page not found.</p>} />
      </Route>
    </Routes>
  );
}
