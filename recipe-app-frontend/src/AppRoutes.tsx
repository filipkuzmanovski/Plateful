import { Route, Routes } from 'react-router';
import { SearchX } from 'lucide-react';
import { EmptyState } from '@/components/ui';
import AppShell from '@/components/AppShell';
import { ProtectedRoute } from '@/auth/ProtectedRoute';
import LoginPage from '@/features/auth/LoginPage';
import SignupPage from '@/features/auth/SignupPage';
import VerifyTwoFactorPage from '@/features/auth/VerifyTwoFactorPage';
import CompleteProfilePage from '@/features/profile/CompleteProfilePage';
import BrowsePage from '@/features/browse/BrowsePage';
import RecipeDetailPage from '@/features/recipe/RecipeDetailPage';
import RecipeFormPage from '@/features/authoring/RecipeFormPage';
import MyProfilePage from '@/features/profile/MyProfilePage';
import PublicProfilePage from '@/features/profile/PublicProfilePage';
import AboutPage from '@/features/content/AboutPage';
import PrivacyPage from '@/features/content/PrivacyPage';
import TermsPage from '@/features/content/TermsPage';
import ContactPage from '@/features/content/ContactPage';
import { AdminRoute } from '@/features/admin/AdminRoute';
import ReviewQueuePage from '@/features/admin/ReviewQueuePage';
import ReviewRecipePage from '@/features/admin/ReviewRecipePage';

export default function AppRoutes() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/" element={<BrowsePage />} />
        <Route path="/recipes/new" element={<ProtectedRoute key="recipe-new"><RecipeFormPage /></ProtectedRoute>} />
        <Route path="/recipes/:id" element={<RecipeDetailPage />} />
        <Route path="/recipes/:id/edit" element={<ProtectedRoute key="recipe-edit"><RecipeFormPage /></ProtectedRoute>} />
        <Route path="/users/:id" element={<PublicProfilePage />} />
        <Route path="/me" element={<ProtectedRoute><MyProfilePage /></ProtectedRoute>} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/complete-profile" element={<ProtectedRoute><CompleteProfilePage /></ProtectedRoute>} />
        {/* Deliberately not wrapped in ProtectedRoute: the page handles every auth status itself. */}
        <Route path="/verify-2fa" element={<VerifyTwoFactorPage />} />
        <Route path="/about" element={<AboutPage />} />
        <Route path="/privacy" element={<PrivacyPage />} />
        <Route path="/terms" element={<TermsPage />} />
        <Route path="/contact" element={<ContactPage />} />
        <Route path="/admin/review" element={<AdminRoute><ReviewQueuePage /></AdminRoute>} />
        <Route path="/admin/review/:id" element={<AdminRoute><ReviewRecipePage /></AdminRoute>} />
        <Route path="*" element={<EmptyState icon={SearchX}>Page not found.</EmptyState>} />
      </Route>
    </Routes>
  );
}
