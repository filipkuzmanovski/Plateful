import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { recipeFixture, pageOf } from '@/test/fixtures';

const BASE = 'http://localhost:8080';

function captureRecipesUrl(store: { url: string }, body = pageOf([recipeFixture()])) {
  server.use(
    http.get(`${BASE}/api/recipes`, ({ request }) => {
      store.url = request.url;
      return HttpResponse.json(body);
    }),
  );
}

test('renders recipe cards from the API', async () => {
  captureRecipesUrl({ url: '' });
  renderApp('/');
  expect(await screen.findByText('Bruschetta Pork & Pasta')).toBeInTheDocument();
  expect(screen.getByText(/4\.6/)).toBeInTheDocument();
  expect(screen.getByText(/35 min/i)).toBeInTheDocument();
});

test('setting a filter updates the request params', async () => {
  const store = { url: '' };
  captureRecipesUrl(store);
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');

  await userEvent.selectOptions(screen.getByLabelText(/cuisine/i), 'italian');
  await userEvent.click(screen.getByRole('button', { name: /vegan/i }));

  await vi.waitFor(() => {
    const params = new URL(store.url).searchParams;
    expect(params.get('cuisine')).toBe('italian');
    expect(params.get('vegan')).toBe('true');
    expect(params.get('page')).toBe('0'); // filter change resets page
  });
});

test('filters initialize from the URL', async () => {
  const store = { url: '' };
  captureRecipesUrl(store);
  renderApp('/?cuisine=thai&maxReadyInMinutes=30&page=1');
  await screen.findByText('Bruschetta Pork & Pasta');
  const params = new URL(store.url).searchParams;
  expect(params.get('cuisine')).toBe('thai');
  expect(params.get('maxReadyInMinutes')).toBe('30');
  expect(params.get('page')).toBe('1');
});

test('empty result shows the empty state', async () => {
  captureRecipesUrl({ url: '' }, pageOf([]));
  renderApp('/');
  expect(await screen.findByText(/no recipes match/i)).toBeInTheDocument();
});

test('pagination renders page buttons from totalPages', async () => {
  captureRecipesUrl({ url: '' }, pageOf([recipeFixture()], { totalPages: 3, number: 0 }));
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  expect(screen.getByRole('button', { name: 'Go to page 3' })).toBeInTheDocument();
});
