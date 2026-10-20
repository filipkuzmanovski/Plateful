import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { recipeFixture, profileFixture, pageOf } from '@/test/fixtures';
import type { RecipeReview } from '@/lib/types';

const BASE = 'http://localhost:8080';

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({
        data: { session: { access_token: 'test-token', user: { id: 'u-1', email: 'jane@example.com' } } },
      }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
      signOut: async () => {},
    },
  },
}));

function stubPage(recipe = recipeFixture()) {
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/recipes/r-1`, () => HttpResponse.json(recipe)),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/r-1/comments/reactions/me`, () => HttpResponse.json({})),
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
  );
}

const review = (status: RecipeReview['status']): RecipeReview => ({
  status, rejectionReason: status === 'rejected' ? 'Not a recipe.' : null, everApproved: false,
  revision: 1, submittedAt: '2026-10-18T10:00:00Z',
});

describe('Share on the recipe page', () => {
  test('shows for a public recipe with review: null', async () => {
    stubPage(recipeFixture({ review: null }));
    renderApp('/recipes/r-1');
    expect(await screen.findByRole('button', { name: 'Share' })).toBeInTheDocument();
  });

  test('shows for an approved recipe', async () => {
    stubPage(recipeFixture({ user: profileFixture, review: review('approved') }));
    renderApp('/recipes/r-1');
    expect(await screen.findByRole('button', { name: 'Share' })).toBeInTheDocument();
  });

  test.each(['pending', 'rejected'] as const)("is hidden for the author's %s recipe", async (status) => {
    stubPage(recipeFixture({ user: profileFixture, review: review(status) }));
    renderApp('/recipes/r-1');
    await screen.findByRole('button', { name: /save/i });
    expect(screen.queryByRole('button', { name: 'Share' })).not.toBeInTheDocument();
  });
});
