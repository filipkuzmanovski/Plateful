import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { recipeFixture, commentFixture, pageOf } from '@/test/fixtures';

const BASE = 'http://localhost:8080';

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({ data: { session: null } }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
      signOut: async () => {},
    },
  },
}));

test('signed-out visitors see the like and dislike buttons disabled, with a visible disabled cue', async () => {
  server.use(
    http.get(`${BASE}/api/recipes/r-1`, () => HttpResponse.json(recipeFixture())),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf([commentFixture()]))),
  );
  renderApp('/recipes/r-1');
  const like = await screen.findByRole('button', { name: /^like comment$/i });
  const dislike = screen.getByRole('button', { name: /^dislike comment$/i });
  expect(like).toBeDisabled();
  expect(dislike).toBeDisabled();
  // The disabled state must not look enabled (it once lost its dimmed cue).
  expect(like.className).toMatch(/disabled:opacity-/);
});
