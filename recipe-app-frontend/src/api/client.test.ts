import { http, HttpResponse } from 'msw';
import { server } from '@/test/server';
import { api, ApiError, setMfaRequiredHandler } from '@/api/client';
import { browseRecipes } from '@/api/recipes';
import { getCuisines, getSeas } from '@/api/cuisines';
import { getMyRating } from '@/api/ratings';
import { recipeFixture, pageOf } from '@/test/fixtures';

const { signOut } = vi.hoisted(() => ({ signOut: vi.fn(async () => {}) }));

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({ data: { session: null } }),
      signOut,
    },
  },
}));

const BASE = 'http://localhost:8080';

beforeEach(() => {
  signOut.mockClear();
  setMfaRequiredHandler(null);
});

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

test('ApiError carries code and approvedTitle from a hidden-recipe 404', async () => {
  server.use(
    http.get(`${BASE}/api/ping`, () =>
      HttpResponse.json(
        { status: 404, detail: 'Under review', code: 'under_review', approvedTitle: 'Old Pie' },
        { status: 404 },
      ),
    ),
  );
  const err = await api('/api/ping').catch((e) => e as unknown);
  expect(err).toBeInstanceOf(ApiError);
  if (err instanceof ApiError) {
    expect(err.code).toBe('under_review');
    expect(err.approvedTitle).toBe('Old Pie');
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
  const result = await browseRecipes({ cuisine: ['italian'], vegan: true }, 2);
  const params = new URL(url).searchParams;
  expect(params.get('cuisine')).toBe('italian');
  expect(params.get('vegan')).toBe('true');
  expect(params.get('dairyFree')).toBeNull(); // unset filter omitted entirely
  expect(params.get('page')).toBe('2');
  expect(params.get('size')).toBe('12');
  expect(result.content[0].title).toBe('Bruschetta Pork & Pasta');
});

test('browseRecipes sends repeated cuisine and region params', async () => {
  let url = '';
  server.use(
    http.get(`${BASE}/api/recipes`, ({ request }) => {
      url = request.url;
      return HttpResponse.json(pageOf([recipeFixture()]));
    }),
  );
  await browseRecipes({ cuisine: ['italian', 'greek'], region: ['asia', 'europe'] }, 0, 1);
  const params = new URL(url).searchParams;
  expect(params.getAll('cuisine')).toEqual(['italian', 'greek']);
  expect(params.getAll('region')).toEqual(['asia', 'europe']);
  expect(params.get('size')).toBe('1');
  expect(params.has('sort')).toBe(false);
  expect(params.get('sortBy')).toBe('newest');
});

test('browseRecipes sends sortBy and repeated sea', async () => {
  let url = '';
  server.use(
    http.get(`${BASE}/api/recipes`, ({ request }) => {
      url = request.url;
      return HttpResponse.json(pageOf([recipeFixture()]));
    }),
  );
  await browseRecipes({ sea: ['pacific', 'caribbean'] }, 0, 12, 'most-rated');
  const params = new URL(url).searchParams;
  expect(params.get('sortBy')).toBe('most-rated');
  expect(params.has('sort')).toBe(false);
  expect(params.getAll('sea')).toEqual(['pacific', 'caribbean']);
});

test('getSeas fetches the sea catalogue', async () => {
  const seas = [
    { key: 'caribbean', label: 'Caribbean', vibe: 'v', starIngredients: 'a', iconDishes: 'b' },
  ];
  server.use(http.get(`${BASE}/api/recipes/seas`, () => HttpResponse.json(seas)));
  expect(await getSeas()).toEqual(seas);
});

test('getCuisines fetches the catalogue', async () => {
  server.use(
    http.get(`${BASE}/api/recipes/cuisines`, () =>
      HttpResponse.json([
        { key: 'italian', label: 'Italian', region: 'europe', seas: ['mediterranean'], count: 40 },
      ]),
    ),
  );
  const cuisines = await getCuisines();
  expect(cuisines).toEqual([
    { key: 'italian', label: 'Italian', region: 'europe', seas: ['mediterranean'], count: 40 },
  ]);
});

test('getMyRating maps 404 to null', async () => {
  server.use(
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'no rating' }, { status: 404 }),
    ),
  );
  expect(await getMyRating('r-1')).toBeNull();
});

test('mfaRequired401DoesNotSignOut', async () => {
  server.use(
    http.post(`${BASE}/api/ping`, () =>
      HttpResponse.json(
        { status: 401, detail: 'Two-factor verification required', code: 'mfa_required' },
        { status: 401 },
      ),
    ),
  );
  const handler = vi.fn();
  setMfaRequiredHandler(handler);
  const err = await api('/api/ping', { method: 'POST', body: JSON.stringify({}) }).catch((e) => e as unknown);
  expect(signOut).not.toHaveBeenCalled();
  expect(handler).toHaveBeenCalledTimes(1);
  expect(err).toBeInstanceOf(ApiError);
  if (err instanceof ApiError) {
    expect(err.code).toBe('mfa_required');
    expect(err.status).toBe(401);
  }
});

test('plain401StillSignsOut', async () => {
  server.use(
    http.get(`${BASE}/api/ping`, () =>
      HttpResponse.json({ status: 401, detail: 'Unauthorized' }, { status: 401 }),
    ),
  );
  const handler = vi.fn();
  setMfaRequiredHandler(handler);
  const err = await api('/api/ping').catch((e) => e as unknown);
  expect(signOut).toHaveBeenCalledTimes(1);
  expect(handler).not.toHaveBeenCalled();
  expect(err).toBeInstanceOf(ApiError);
  if (err instanceof ApiError) {
    expect(err.status).toBe(401);
    expect(err.code).toBeUndefined();
  }
});
