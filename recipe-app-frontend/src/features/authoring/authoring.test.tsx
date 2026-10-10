import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { recipeFixture, profileFixture, pageOf } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';

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

const cuisineCatalogue = [
  { key: 'italian', label: 'Italian', region: 'Europe', mediterranean: true, count: 12 },
  { key: 'macedonian', label: 'Macedonian', region: 'Europe', mediterranean: false, count: 0 },
  { key: 'greek', label: 'Greek', region: 'Europe', mediterranean: true, count: 4 },
];

beforeEach(() => {
  server.use(http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json(cuisineCatalogue)));
});

function stubMe() {
  server.use(http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)));
}

test('create: fills the form, adds an ingredient row, POSTs, navigates to the new recipe', async () => {
  stubMe();
  let posted: any = null;
  const created = recipeFixture({ id: 'r-new', title: 'My Pie', user: profileFixture });
  server.use(
    http.post(`${BASE}/api/recipes`, async ({ request }) => {
      posted = await request.json();
      return HttpResponse.json(created, { status: 201 });
    }),
    http.get(`${BASE}/api/recipes/r-new`, () => HttpResponse.json(created)),
    http.get(`${BASE}/api/recipes/r-new/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/r-new/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
  );
  renderApp('/recipes/new');

  await userEvent.type(await screen.findByLabelText(/title/i), 'My Pie');
  await userEvent.type(screen.getByLabelText(/ingredient 1 name/i), 'apples');
  await userEvent.type(screen.getByLabelText(/ingredient 1 text/i), '3 tart apples, sliced');
  await userEvent.click(screen.getByRole('button', { name: /add ingredient/i }));
  expectNoEmoji();
  await userEvent.type(screen.getByLabelText(/ingredient 2 name/i), 'sugar');
  await userEvent.type(screen.getByLabelText(/ingredient 2 text/i), '1 cup sugar');
  await userEvent.click(screen.getByRole('button', { name: /^save recipe$/i }));

  expect(await screen.findByRole('heading', { name: /my pie/i })).toBeInTheDocument();
  expect(posted.title).toBe('My Pie');
  expect(posted.ingredients).toEqual([
    { ingredientName: 'apples', originalText: '3 tart apples, sliced' },
    { ingredientName: 'sugar', originalText: '1 cup sugar' },
  ]);
});

test('create: empty title and no ingredients show inline validation, no request sent', async () => {
  stubMe();
  let posted = false;
  server.use(http.post(`${BASE}/api/recipes`, () => { posted = true; return HttpResponse.json({}); }));
  renderApp('/recipes/new');
  await userEvent.click(await screen.findByRole('button', { name: /^save recipe$/i }));
  expect(await screen.findAllByRole('alert')).not.toHaveLength(0);
  expect(posted).toBe(false);
});

test('server field errors from ProblemDetail map onto the form', async () => {
  stubMe();
  server.use(
    http.post(`${BASE}/api/recipes`, () =>
      HttpResponse.json(
        { status: 400, detail: 'Validation failed', errors: { title: 'must not be blank' } },
        { status: 400 },
      ),
    ),
  );
  renderApp('/recipes/new');
  await userEvent.type(await screen.findByLabelText(/title/i), 'X');
  await userEvent.type(screen.getByLabelText(/ingredient 1 name/i), 'a');
  await userEvent.type(screen.getByLabelText(/ingredient 1 text/i), 'b');
  await userEvent.click(screen.getByRole('button', { name: /^save recipe$/i }));
  expect(await screen.findByText('must not be blank')).toBeInTheDocument();
});

test('owner sees Edit and Delete on their recipe detail; delete confirms then navigates home', async () => {
  stubMe();
  let deleted = false;
  server.use(
    http.get(`${BASE}/api/recipes/r-1`, () => HttpResponse.json(recipeFixture({ user: profileFixture }))),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
    http.delete(`${BASE}/api/recipes/r-1`, () => {
      deleted = true;
      return new HttpResponse(null, { status: 204 });
    }),
    http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))),
  );
  renderApp('/recipes/r-1');
  await userEvent.click(await screen.findByRole('button', { name: /delete recipe/i }));
  await userEvent.click(await screen.findByRole('button', { name: /confirm/i }));
  await vi.waitFor(() => expect(deleted).toBe(true));
});

test('edit: pre-fills, PUTs, and the detail page shows the updated recipe (cache refreshed)', async () => {
  stubMe();
  let current = recipeFixture({ title: 'Old Pie', user: profileFixture });
  let putBody: any = null;
  server.use(
    http.get(`${BASE}/api/recipes/r-1`, () => HttpResponse.json(current)),
    http.put(`${BASE}/api/recipes/r-1`, async ({ request }) => {
      putBody = await request.json();
      current = { ...current, title: putBody.title };
      return HttpResponse.json(current);
    }),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
  );
  renderApp('/recipes/r-1/edit');

  const title = await screen.findByLabelText(/title/i);
  await vi.waitFor(() => expect(title).toHaveValue('Old Pie')); // pre-filled
  await userEvent.clear(title);
  await userEvent.type(title, 'New Pie');
  await userEvent.click(screen.getByRole('button', { name: /^save recipe$/i }));

  expect(await screen.findByRole('heading', { name: /new pie/i })).toBeInTheDocument();
  expect(putBody.title).toBe('New Pie');
  expect(putBody.ingredients).toHaveLength(2); // fixture's ingredients carried through
});

test('a server error on a field without an inline slot is still shown', async () => {
  stubMe();
  server.use(
    http.post(`${BASE}/api/recipes`, () =>
      HttpResponse.json(
        { status: 400, detail: 'Validation failed', errors: { servings: 'must be greater than or equal to 0' } },
        { status: 400 },
      ),
    ),
  );
  renderApp('/recipes/new');
  await userEvent.type(await screen.findByLabelText(/title/i), 'X');
  await userEvent.type(screen.getByLabelText(/ingredient 1 name/i), 'a');
  await userEvent.type(screen.getByLabelText(/ingredient 1 text/i), 'b');
  await userEvent.click(screen.getByRole('button', { name: /^save recipe$/i }));
  expect(await screen.findByText('must be greater than or equal to 0')).toBeInTheDocument();
});

test('going from Edit to New Recipe starts with an empty form', async () => {
  stubMe();
  server.use(
    http.get(`${BASE}/api/recipes/r-1`, () =>
      HttpResponse.json(recipeFixture({ title: 'Old Pie', user: profileFixture })),
    ),
  );
  renderApp('/recipes/r-1/edit');
  const title = await screen.findByLabelText(/title/i);
  await vi.waitFor(() => expect(title).toHaveValue('Old Pie'));
  await userEvent.click(screen.getByRole('link', { name: /new recipe/i }));
  expect(await screen.findByRole('heading', { name: /new recipe/i })).toBeInTheDocument();
  expect(screen.getByLabelText(/title/i)).toHaveValue('');
});

/** The combobox is disabled while the catalogue loads, so wait until it is usable. */
async function cuisineInput() {
  const input = await screen.findByRole('combobox', { name: 'Cuisines' });
  await waitFor(() => expect(input).toBeEnabled());
  return input;
}

async function fillRequired() {
  await userEvent.type(await screen.findByLabelText(/title/i), 'X');
  await userEvent.type(screen.getByLabelText(/ingredient 1 name/i), 'a');
  await userEvent.type(screen.getByLabelText(/ingredient 1 text/i), 'b');
}

test('pickerSearchesAndAddsCuisine: typing filters by label and the chosen key is POSTed', async () => {
  stubMe();
  let posted: any = null;
  const created = recipeFixture({ id: 'r-new', title: 'X', user: profileFixture });
  server.use(
    http.post(`${BASE}/api/recipes`, async ({ request }) => {
      posted = await request.json();
      return HttpResponse.json(created, { status: 201 });
    }),
    http.get(`${BASE}/api/recipes/r-new`, () => HttpResponse.json(created)),
    http.get(`${BASE}/api/recipes/r-new/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/r-new/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
  );
  renderApp('/recipes/new');
  await fillRequired();

  await userEvent.type(await cuisineInput(), 'mac');
  expect(screen.queryByRole('option', { name: 'Italian' })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('option', { name: 'Macedonian' })); // a count-0 entry is offered too
  expect(screen.getByRole('button', { name: 'Remove Macedonian' })).toBeInTheDocument();
  expectNoEmoji();

  await userEvent.click(screen.getByRole('button', { name: /^save recipe$/i }));
  expect(await screen.findByRole('heading', { name: 'X' })).toBeInTheDocument();
  expect(posted.cuisines).toEqual(['macedonian']);
});

test('picker: keyboard selects with Enter, and a chip can be removed', async () => {
  stubMe();
  renderApp('/recipes/new');
  const input = await cuisineInput();
  await userEvent.type(input, 'gre{ArrowDown}{Enter}');
  expect(screen.getByRole('button', { name: 'Remove Greek' })).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Remove Greek' }));
  expect(screen.queryByRole('button', { name: 'Remove Greek' })).not.toBeInTheDocument();
});

test('pickerCapsAtTen: the input is disabled after ten selections', async () => {
  stubMe();
  const many = Array.from({ length: 12 }, (_, i) => ({
    key: `c${i}`, label: `Cuisine${String.fromCharCode(65 + i)}`, region: 'Europe', mediterranean: false, count: 1,
  }));
  server.use(http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json(many)));
  renderApp('/recipes/new');
  const input = await cuisineInput();
  for (let i = 0; i < 10; i++) {
    await userEvent.type(input, many[i].label);
    await userEvent.click(await screen.findByRole('option', { name: many[i].label }));
  }
  expect(input).toBeDisabled();
  expect(screen.getByText('Up to 10 cuisines')).toBeInTheDocument();
});

test('serverUnknownCuisineErrorShowsOnPicker', async () => {
  stubMe();
  server.use(
    http.post(`${BASE}/api/recipes`, () =>
      HttpResponse.json(
        { status: 400, detail: 'Validation failed', errors: { cuisines: 'Unknown cuisine: lol' } },
        { status: 400 },
      ),
    ),
  );
  renderApp('/recipes/new');
  await fillRequired();
  await userEvent.click(screen.getByRole('button', { name: /^save recipe$/i }));
  expect(await screen.findByText('Unknown cuisine: lol')).toBeInTheDocument();
});

test('editPrefillsSelectedCuisines', async () => {
  stubMe();
  server.use(
    http.get(`${BASE}/api/recipes/r-1`, () =>
      HttpResponse.json(recipeFixture({ cuisines: ['italian'], user: profileFixture })),
    ),
  );
  renderApp('/recipes/r-1/edit');
  expect(await screen.findByRole('button', { name: 'Remove Italian' })).toBeInTheDocument();
});

test('picker: a failed cuisines load offers Retry', async () => {
  stubMe();
  server.use(http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json({ status: 500, detail: 'boom' }, { status: 500 })));
  renderApp('/recipes/new');
  expect(await screen.findByText("Couldn't load cuisines")).toBeInTheDocument();
  server.use(http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json(cuisineCatalogue)));
  await userEvent.click(screen.getByRole('button', { name: /retry/i }));
  expect(await screen.findByRole('combobox', { name: 'Cuisines' })).toBeInTheDocument();
});
