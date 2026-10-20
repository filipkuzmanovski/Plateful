import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp, newTestQueryClient } from '@/test/utils';
import { recipeFixture, profileFixture, pageOf } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import { deletePhotos, publicPhotoUrl } from '@/api/photos';
import { deleteVideoFiles, publicVideoUrl } from '@/api/videos';

const BASE = 'http://localhost:8080';

vi.mock('@/api/photos', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/photos')>()),
  deletePhotos: vi.fn(async () => {}),
}));

vi.mock('@/api/videos', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/videos')>()),
  deleteVideoFiles: vi.fn(async () => {}),
}));

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
  await screen.findByRole('button', { name: /rate 4 stars/i }); // rating box loaded
  expectNoEmoji();
});

test('prefixes the ingredient name when the line does not mention it, and shows the line alone otherwise', async () => {
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/recipes/r-1`, () =>
      HttpResponse.json(
        recipeFixture({
          ingredients: [
            { ingredientName: 'flour', originalText: '2 cups, sifted' },
            { ingredientName: 'garlic', originalText: '3 cloves garlic, minced' },
          ],
        }),
      ),
    ),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
  );
  renderApp('/recipes/r-1');
  const flour = await screen.findByText('flour');
  expect(flour.tagName).toBe('STRONG');
  expect(screen.getByRole('checkbox', { name: 'flour, 2 cups, sifted' })).toBeInTheDocument();
  expect(screen.getByText(/^, 2 cups, sifted$/)).toBe(flour.parentElement);

  const garlicLine = screen.getByText('3 cloves garlic, minced');
  expect(garlicLine.querySelector('strong')).toBeNull();
  expectNoEmoji();
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

test('rating a recipe marks the saved list stale (its card shows the average)', async () => {
  stubDetail({ myRatingStatus: 404 });
  server.use(
    http.put(`${BASE}/api/recipes/r-1/ratings/me`, () => new HttpResponse(null, { status: 204 })),
  );
  const queryClient = newTestQueryClient();
  queryClient.setQueryData(['savedRecipes', 'u-1', 0], pageOf([recipeFixture()]));
  renderApp('/recipes/r-1', queryClient);
  await userEvent.click(await screen.findByRole('button', { name: /rate 5 stars/i }));
  await vi.waitFor(() =>
    expect(queryClient.getQueryState(['savedRecipes', 'u-1', 0])?.isInvalidated).toBe(true),
  );
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

test('a failed rating shows the server error in a toast', async () => {
  stubDetail({ myRatingStatus: 404 });
  server.use(
    http.put(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'Recipe with id r-1 not found' }, { status: 404 }),
    ),
  );
  renderApp('/recipes/r-1');
  await userEvent.click(await screen.findByRole('button', { name: /rate 5 stars/i }));
  expect(await screen.findByText('Recipe with id r-1 not found')).toBeInTheDocument();
});

test('an imported recipe without a source URL shows its source as plain text', async () => {
  stubDetail();
  server.use(
    http.get(`${BASE}/api/recipes/r-1`, () => HttpResponse.json(recipeFixture({ sourceUrl: null }))),
  );
  renderApp('/recipes/r-1');
  expect(await screen.findByText(/from pink when/i)).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: /from pink when/i })).not.toBeInTheDocument();
});

test('deletingRecipeDeletesItsPhotos: after the delete resolves, the bucket photos are removed from storage', async () => {
  vi.mocked(deletePhotos).mockClear();
  const log: string[] = [];
  vi.mocked(deletePhotos).mockImplementation(async () => { log.push('photos'); });
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/recipes/r-1`, () =>
      HttpResponse.json(
        recipeFixture({
          user: profileFixture,
          photos: [publicPhotoUrl('u-1/a.webp'), 'https://img.spoonacular.com/x.jpg', publicPhotoUrl('u-1/b.png')],
        }),
      ),
    ),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
    http.delete(`${BASE}/api/recipes/r-1`, () => {
      log.push('api');
      return new HttpResponse(null, { status: 204 });
    }),
    http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))),
  );
  renderApp('/recipes/r-1');
  await userEvent.click(await screen.findByRole('button', { name: /delete recipe/i }));
  await userEvent.click(await screen.findByRole('button', { name: /confirm/i }));
  await vi.waitFor(() => expect(deletePhotos).toHaveBeenCalledWith(['u-1/a.webp', 'u-1/b.png']));
  expect(log).toEqual(['api', 'photos']);
});

test('deleting a recipe marks the saved list stale, so no ghost card is served from cache', async () => {
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/recipes/r-1`, () => HttpResponse.json(recipeFixture({ user: profileFixture }))),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
    http.delete(`${BASE}/api/recipes/r-1`, () => new HttpResponse(null, { status: 204 })),
    http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))),
  );
  const queryClient = newTestQueryClient();
  queryClient.setQueryData(['savedRecipes', 'u-1', 0], pageOf([recipeFixture()]));
  renderApp('/recipes/r-1', queryClient);
  await userEvent.click(await screen.findByRole('button', { name: /delete recipe/i }));
  await userEvent.click(await screen.findByRole('button', { name: /confirm/i }));
  await vi.waitFor(() =>
    expect(queryClient.getQueryState(['savedRecipes', 'u-1', 0])?.isInvalidated).toBe(true),
  );
});

test('a failed recipe delete leaves its photos alone', async () => {
  vi.mocked(deletePhotos).mockClear();
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/recipes/r-1`, () =>
      HttpResponse.json(recipeFixture({ user: profileFixture, photos: [publicPhotoUrl('u-1/a.webp')] })),
    ),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
    http.delete(`${BASE}/api/recipes/r-1`, () => HttpResponse.json({ status: 500, detail: 'boom' }, { status: 500 })),
  );
  renderApp('/recipes/r-1');
  await userEvent.click(await screen.findByRole('button', { name: /delete recipe/i }));
  await userEvent.click(await screen.findByRole('button', { name: /confirm/i }));
  expect(await screen.findByText('boom')).toBeInTheDocument();
  expect(deletePhotos).not.toHaveBeenCalled();
});

function stubVideoRecipeDelete(deleteResponse: () => Response) {
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/recipes/r-1`, () =>
      HttpResponse.json(
        recipeFixture({
          user: profileFixture,
          video: { url: publicVideoUrl('u-1/clip.mp4'), posterUrl: publicVideoUrl('u-1/clip.jpg'), durationSeconds: 30 },
        }),
      ),
    ),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
    http.delete(`${BASE}/api/recipes/r-1`, deleteResponse),
    http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))),
  );
}

test('deletingRecipeDeletesItsVideo: after the delete resolves, the clip and its poster are removed from storage', async () => {
  vi.mocked(deleteVideoFiles).mockClear();
  const log: string[] = [];
  vi.mocked(deleteVideoFiles).mockImplementation(async () => { log.push('video'); });
  stubVideoRecipeDelete(() => {
    log.push('api');
    return new HttpResponse(null, { status: 204 });
  });
  renderApp('/recipes/r-1');
  await userEvent.click(await screen.findByRole('button', { name: /delete recipe/i }));
  await userEvent.click(await screen.findByRole('button', { name: /confirm/i }));
  await vi.waitFor(() => expect(deleteVideoFiles).toHaveBeenCalledWith(['u-1/clip.mp4', 'u-1/clip.jpg']));
  expect(deleteVideoFiles).toHaveBeenCalledTimes(1);
  expect(log).toEqual(['api', 'video']);
});

test('a failed recipe delete leaves its video alone', async () => {
  vi.mocked(deleteVideoFiles).mockClear();
  stubVideoRecipeDelete(() => HttpResponse.json({ status: 500, detail: 'boom' }, { status: 500 }));
  renderApp('/recipes/r-1');
  await userEvent.click(await screen.findByRole('button', { name: /delete recipe/i }));
  await userEvent.click(await screen.findByRole('button', { name: /confirm/i }));
  expect(await screen.findByText('boom')).toBeInTheDocument();
  expect(deleteVideoFiles).not.toHaveBeenCalled();
});
