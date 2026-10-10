import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { recipeFixture, profileFixture, pageOf } from '@/test/fixtures';

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

function stubDetail({ myRatingStatus = 200, myRating = 4 } = {}) {
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/recipes/r-1`, () => HttpResponse.json(recipeFixture())),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      myRatingStatus === 200
        ? HttpResponse.json({ rating: myRating })
        : HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
  );
}

test('renders title, ingredients, structured steps, badges and macros', async () => {
  stubDetail();
  renderApp('/recipes/r-1');
  expect(await screen.findByRole('heading', { name: /bruschetta pork & pasta/i })).toBeInTheDocument();
  expect(screen.getByText('8 oz penne pasta')).toBeInTheDocument();
  expect(screen.getByText(/boil the pasta/i)).toBeInTheDocument();
  expect(screen.getByText(/gluten free/i)).toBeInTheDocument();
  expect(screen.getByText(/543/)).toBeInTheDocument(); // calories
  expect(screen.getByText(/from pink when/i)).toBeInTheDocument();
});

test('rating box pre-fills my rating and un-rates on clicking the same star', async () => {
  stubDetail({ myRating: 4 });
  let deleted = false;
  server.use(
    http.delete(`${BASE}/api/recipes/r-1/ratings/me`, () => {
      deleted = true;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  renderApp('/recipes/r-1');
  const star4 = await screen.findByRole('button', { name: /rate 4 stars/i });
  expect(star4).toHaveAttribute('aria-pressed', 'true');
  await userEvent.click(star4);
  await vi.waitFor(() => expect(deleted).toBe(true));
});

test('clicking a new star PUTs the rating', async () => {
  stubDetail({ myRatingStatus: 404 });
  let putBody: unknown = null;
  server.use(
    http.put(`${BASE}/api/recipes/r-1/ratings/me`, async ({ request }) => {
      putBody = await request.json();
      return new HttpResponse(null, { status: 204 });
    }),
  );
  renderApp('/recipes/r-1');
  await userEvent.click(await screen.findByRole('button', { name: /rate 5 stars/i }));
  await vi.waitFor(() => expect(putBody).toEqual({ rating: 5 }));
});

test('unknown recipe id shows not-found message', async () => {
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/recipes/nope`, () =>
      HttpResponse.json({ status: 404, detail: 'Recipe not found' }, { status: 404 }),
    ),
  );
  renderApp('/recipes/nope');
  expect(await screen.findByText(/recipe not found/i)).toBeInTheDocument();
});
