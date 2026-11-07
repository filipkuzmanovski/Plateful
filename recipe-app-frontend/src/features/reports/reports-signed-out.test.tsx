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

test('signed-out visitors see no Report buttons and never ask for /mine', async () => {
  let asked = false;
  server.use(
    http.get(`${BASE}/api/recipes/r-1`, () =>
      HttpResponse.json(recipeFixture({ user: { id: 'u-2', firstName: 'Bob', lastName: 'Smith' } })),
    ),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf([commentFixture()]))),
    http.get(`${BASE}/api/reports/mine`, () => {
      asked = true;
      return HttpResponse.json({ recipeIds: [], commentIds: [] });
    }),
  );
  renderApp('/recipes/r-1');
  await screen.findByText('Delicious!');
  expect(screen.queryByRole('button', { name: /report/i })).not.toBeInTheDocument();
  expect(asked).toBe(false);
});
