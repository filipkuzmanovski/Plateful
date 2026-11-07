import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { recipeFixture, profileFixture, commentFixture, pageOf } from '@/test/fixtures';
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

const otherUser = { id: 'u-2', firstName: 'Bob', lastName: 'Smith' };

function stubPage({
  comments = [commentFixture({ id: 'c-9', user: otherUser })],
  recipeUser = otherUser as typeof otherUser | null,
  mine = { recipeIds: [] as string[], commentIds: [] as string[] },
} = {}) {
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/recipes/r-1`, () => HttpResponse.json(recipeFixture({ user: recipeUser }))),
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf(comments))),
    http.get(`${BASE}/api/recipes/r-1/comments/reactions/me`, () => HttpResponse.json({})),
    http.get(`${BASE}/api/reports/mine`, () => HttpResponse.json(mine)),
  );
}

function stubReport(status: number, code?: string) {
  const sent: unknown[] = [];
  server.use(
    http.post(`${BASE}/api/reports`, async ({ request }) => {
      sent.push(await request.json());
      return status === 204
        ? new HttpResponse(null, { status })
        : HttpResponse.json({ status, detail: 'nope', code }, { status });
    }),
  );
  return sent;
}

async function openRecipeDialog() {
  await userEvent.click(await screen.findByRole('button', { name: /^report$/i }));
  return screen.findByRole('dialog');
}

async function send(dialog: HTMLElement, reason = 'Spam') {
  await userEvent.click(within(dialog).getByRole('radio', { name: reason }));
  await userEvent.click(within(dialog).getByRole('button', { name: /send report/i }));
}

test('the dialog lists the five reasons in order', async () => {
  stubPage();
  renderApp('/recipes/r-1');
  const dialog = await openRecipeDialog();
  const labels = within(dialog).getAllByRole('radio').map((r) => r.closest('label')?.textContent);
  expect(labels).toEqual(['Spam', 'Offensive or hateful', 'Unsafe food advice', 'Not food related', 'Other']);
  expectNoEmoji();
});

test('the note counter stops at 500', async () => {
  stubPage();
  renderApp('/recipes/r-1');
  const dialog = await openRecipeDialog();
  const note = within(dialog).getByLabelText(/note/i);
  expect(note).toHaveAttribute('maxlength', '500');
  expect(within(dialog).getByText('0/500')).toBeInTheDocument();
  await userEvent.click(note);
  await userEvent.paste('x'.repeat(600));
  expect(within(dialog).getByText('500/500')).toBeInTheDocument();
});

test('sending posts the exact body, thanks the user and disables the button', async () => {
  stubPage();
  const sent = stubReport(204);
  renderApp('/recipes/r-1');
  const dialog = await openRecipeDialog();
  await userEvent.type(within(dialog).getByLabelText(/note/i), 'looks fake');
  await send(dialog);
  expect(await screen.findByText('Thanks. An admin will take a look.')).toBeInTheDocument();
  expect(sent).toEqual([{ recipeId: 'r-1', reason: 'spam', note: 'looks fake' }]);
  await userEvent.click(screen.getByRole('button', { name: /^close$/i }));
  expect(await screen.findByRole('button', { name: /^reported$/i })).toBeDisabled();
});

test('a blank note is sent as null', async () => {
  stubPage();
  const sent = stubReport(204);
  renderApp('/recipes/r-1');
  const dialog = await openRecipeDialog();
  await send(dialog, 'Other');
  await screen.findByText('Thanks. An admin will take a look.');
  expect(sent).toEqual([{ recipeId: 'r-1', reason: 'other', note: null }]);
});

test('a 409 says already reported and switches the button to Reported', async () => {
  stubPage();
  stubReport(409, 'already_reported');
  renderApp('/recipes/r-1');
  await send(await openRecipeDialog());
  expect(await screen.findByText("You've already reported this.")).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: /^close$/i }));
  expect(await screen.findByRole('button', { name: /^reported$/i })).toBeDisabled();
});

test('a 429 explains the daily limit', async () => {
  stubPage();
  stubReport(429, 'report_limit');
  renderApp('/recipes/r-1');
  await send(await openRecipeDialog());
  expect(await screen.findByText("You've sent 20 reports today. You can report more tomorrow.")).toBeInTheDocument();
});

test('a 404 says the item is no longer available', async () => {
  stubPage();
  stubReport(404);
  renderApp('/recipes/r-1');
  await send(await openRecipeDialog());
  expect(await screen.findByText('This is no longer available.')).toBeInTheDocument();
});

test('reporting a comment posts its id', async () => {
  stubPage();
  const sent = stubReport(204);
  renderApp('/recipes/r-1');
  await userEvent.click(await screen.findByRole('button', { name: /report comment by bob smith/i }));
  await send(await screen.findByRole('dialog'), 'Offensive or hateful');
  await screen.findByText('Thanks. An admin will take a look.');
  expect(sent).toEqual([{ commentId: 'c-9', reason: 'offensive', note: null }]);
});

test('no Report on your own recipe or your own comment', async () => {
  stubPage({ recipeUser: profileFixture, comments: [commentFixture()] });
  renderApp('/recipes/r-1');
  await screen.findByText('Delicious!');
  expect(screen.queryByRole('button', { name: /report/i })).not.toBeInTheDocument();
});

test('Reported shows on load when /mine lists the items', async () => {
  stubPage({ mine: { recipeIds: ['r-1'], commentIds: ['c-9'] } });
  renderApp('/recipes/r-1');
  expect(await screen.findByRole('button', { name: /^reported$/i })).toBeDisabled();
  expect(await screen.findByRole('button', { name: /^reported comment by bob smith$/i })).toBeDisabled();
});

test('a hidden own comment carries the note and no Report', async () => {
  stubPage({ comments: [commentFixture({ hidden: true })] });
  renderApp('/recipes/r-1');
  expect(await screen.findByText('Hidden while an admin looks at reports.')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /report comment/i })).not.toBeInTheDocument();
  expectNoEmoji();
});
