import { http, HttpResponse } from 'msw';
import { server } from '@/test/server';
import { api, ApiError } from '@/api/client';
import { browseRecipes } from '@/api/recipes';
import { getMyRating } from '@/api/ratings';
import { recipeFixture, pageOf } from '@/test/fixtures';

const BASE = 'http://localhost:8080';

test('parses JSON and sends no auth header when anonymous', async () => {
  let authHeader: string | null = 'unset';
  server.use(
    http.get(`${BASE}/api/ping`, ({ request }) => {
      authHeader = request.headers.get('Authorization');
      return HttpResponse.json({ ok: true });
    }),
  );
  const result = await api<{ ok: boolean }>('/api/ping');
  expect(result.ok).toBe(true);
  expect(authHeader).toBeNull();
});

test('throws typed ApiError from a ProblemDetail body', async () => {
  server.use(
    http.post(`${BASE}/api/ping`, () =>
      HttpResponse.json(
        { status: 400, detail: 'Validation failed', errors: { title: 'must not be blank' } },
        { status: 400 },
      ),
    ),
  );
  const err = await api('/api/ping', { method: 'POST', body: JSON.stringify({}) }).catch((e) => e as unknown);
  expect(err).toBeInstanceOf(ApiError);
  if (err instanceof ApiError) {
    expect(err.status).toBe(400);
    expect(err.detail).toBe('Validation failed');
    expect(err.errors).toEqual({ title: 'must not be blank' });
  }
});

test('browseRecipes serializes only the set filters and pagination', async () => {
  let url = '';
  server.use(
    http.get(`${BASE}/api/recipes`, ({ request }) => {
      url = request.url;
      return HttpResponse.json(pageOf([recipeFixture()]));
    }),
  );
  const result = await browseRecipes({ cuisine: 'italian', vegan: true }, 2);
  const params = new URL(url).searchParams;
  expect(params.get('cuisine')).toBe('italian');
  expect(params.get('vegan')).toBe('true');
  expect(params.get('dairyFree')).toBeNull(); // unset filter omitted entirely
  expect(params.get('page')).toBe('2');
  expect(params.get('size')).toBe('12');
  expect(result.content[0].title).toBe('Bruschetta Pork & Pasta');
});

test('getMyRating maps 404 to null', async () => {
  server.use(
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'no rating' }, { status: 404 }),
    ),
  );
  expect(await getMyRating('r-1')).toBeNull();
});
