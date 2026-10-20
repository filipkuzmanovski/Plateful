import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp, newTestQueryClient } from '@/test/utils';
import { recipeFixture, profileFixture, commentFixture, pageOf } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import { deletePhotos, publicPhotoUrl } from '@/api/photos';
import { deleteVideoFiles, publicVideoUrl } from '@/api/videos';
import type { MyProfile } from '@/lib/types';

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

const admin: MyProfile = { ...profileFixture, isAdmin: true };
const author = { id: 'u-2', firstName: 'Bob', lastName: 'Smith' };

function stubPage({
  me = profileFixture,
  recipe = recipeFixture({ user: author }),
  comments = [] as ReturnType<typeof commentFixture>[],
} = {}) {
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(me)),
    http.get(`${BASE}/api/recipes/r-1`, () => HttpResponse.json(recipe)),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf(comments))),
    http.get(`${BASE}/api/recipes/r-1/comments/reactions/me`, () => HttpResponse.json({})),
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
    http.get(`${BASE}/api/recipes`, () =>
      HttpResponse.json(pageOf([recipeFixture({ id: 'r-9', title: 'Home Marker' })])),
    ),
  );
}

describe('removing a recipe', () => {
  test("an admin on someone else's recipe sees Remove", async () => {
    stubPage({ me: admin });
    renderApp('/recipes/r-1');
    expect(await screen.findByRole('button', { name: 'Remove' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /delete recipe/i })).not.toBeInTheDocument();
    expectNoEmoji();
  });

  test('an admin on their own recipe sees Delete, not Remove', async () => {
    stubPage({ me: admin, recipe: recipeFixture({ user: admin }) });
    renderApp('/recipes/r-1');
    expect(await screen.findByRole('button', { name: /delete recipe/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument();
  });

  test("a non-admin sees neither Remove nor Delete on someone else's recipe", async () => {
    stubPage();
    renderApp('/recipes/r-1');
    await screen.findByRole('heading', { name: /bruschetta pork & pasta/i });
    expect(screen.queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /delete recipe/i })).not.toBeInTheDocument();
  });

  test('the owner (not an admin) sees Delete, not Remove', async () => {
    stubPage({ recipe: recipeFixture({ user: profileFixture }) });
    renderApp('/recipes/r-1');
    expect(await screen.findByRole('button', { name: /delete recipe/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument();
  });

  test('the dialog needs a non-blank reason, posts it, cleans up storage, toasts and goes home', async () => {
    vi.mocked(deletePhotos).mockClear();
    vi.mocked(deleteVideoFiles).mockClear();
    const log: string[] = [];
    vi.mocked(deletePhotos).mockImplementation(async () => { log.push('photos'); });
    vi.mocked(deleteVideoFiles).mockImplementation(async () => { log.push('video'); });
    stubPage({
      me: admin,
      recipe: recipeFixture({
        user: author,
        photos: [publicPhotoUrl('u-2/a.webp')],
        video: { url: publicVideoUrl('u-2/clip.mp4'), posterUrl: publicVideoUrl('u-2/clip.jpg'), durationSeconds: 30 },
      }),
    });
    let body: unknown = null;
    server.use(
      http.post(`${BASE}/api/admin/recipes/r-1/remove`, async ({ request }) => {
        body = await request.json();
        log.push('api');
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const queryClient = newTestQueryClient();
    const keys = [['recipes', 'x'], ['userRecipes', 'x'], ['savedRecipes', 'x'], ['savedIds', 'u-1'], ['admin', 'pending']];
    for (const key of keys) queryClient.setQueryData(key, key[0] === 'savedIds' ? [] : pageOf([]));
    renderApp('/recipes/r-1', queryClient);

    await userEvent.click(await screen.findByRole('button', { name: 'Remove' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Remove this recipe?')).toBeInTheDocument();
    expect(within(dialog).getByText('The author will get an email with your reason.')).toBeInTheDocument();
    const confirm = within(dialog).getByRole('button', { name: 'Remove recipe' });
    expect(confirm).toBeDisabled();
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Reason' }), '   ');
    expect(confirm).toBeDisabled();
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Reason' }), ' Breaks the rules ');
    expect(within(dialog).getByText('16/500')).toBeInTheDocument();
    expectNoEmoji();
    await userEvent.click(confirm);

    expect(await screen.findByText('Recipe removed')).toBeInTheDocument();
    expect(body).toEqual({ reason: 'Breaks the rules' });
    await waitFor(() => expect(deletePhotos).toHaveBeenCalledWith(['u-2/a.webp']));
    await waitFor(() => expect(deleteVideoFiles).toHaveBeenCalledWith(['u-2/clip.mp4', 'u-2/clip.jpg']));
    expect(log[0]).toBe('api');
    expect(await screen.findByText('Home Marker')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /bruschetta pork & pasta/i })).not.toBeInTheDocument();
    for (const key of keys) expect(queryClient.getQueryState(key)?.isInvalidated).toBe(true);
    expect(queryClient.getQueryData(['recipe', 'r-1'])).toBeUndefined();
  });

  test('a 400 shows the reason error in the dialog and keeps it open', async () => {
    vi.mocked(deletePhotos).mockClear();
    vi.mocked(deleteVideoFiles).mockClear();
    stubPage({
      me: admin,
      recipe: recipeFixture({
        user: author,
        photos: [publicPhotoUrl('u-2/a.webp')],
        video: { url: publicVideoUrl('u-2/clip.mp4'), posterUrl: publicVideoUrl('u-2/clip.jpg'), durationSeconds: 30 },
      }),
    });
    server.use(
      http.post(`${BASE}/api/admin/recipes/r-1/remove`, () =>
        HttpResponse.json(
          { status: 400, detail: 'Validation failed', errors: { reason: 'Reason must be 500 characters or fewer' } },
          { status: 400 },
        ),
      ),
    );
    renderApp('/recipes/r-1');
    await userEvent.click(await screen.findByRole('button', { name: 'Remove' }));
    await userEvent.type(await screen.findByRole('textbox', { name: 'Reason' }), 'Nope');
    await userEvent.click(screen.getByRole('button', { name: 'Remove recipe' }));
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Reason must be 500 characters or fewer');
    expect(deletePhotos).not.toHaveBeenCalled();
    expect(deleteVideoFiles).not.toHaveBeenCalled();
  });

  test('a 404 says it was already removed, forgets the recipe everywhere and goes home', async () => {
    vi.mocked(deletePhotos).mockClear();
    vi.mocked(deleteVideoFiles).mockClear();
    stubPage({
      me: admin,
      recipe: recipeFixture({
        user: author,
        photos: [publicPhotoUrl('u-2/a.webp')],
        video: { url: publicVideoUrl('u-2/clip.mp4'), posterUrl: publicVideoUrl('u-2/clip.jpg'), durationSeconds: 30 },
      }),
    });
    server.use(
      http.post(`${BASE}/api/admin/recipes/r-1/remove`, () =>
        HttpResponse.json({ status: 404, detail: 'Recipe not found' }, { status: 404 }),
      ),
    );
    const queryClient = newTestQueryClient();
    const keys = [['recipes', 'x'], ['userRecipes', 'x'], ['savedRecipes', 'x'], ['savedIds', 'u-1'], ['admin', 'pending']];
    for (const key of keys) queryClient.setQueryData(key, key[0] === 'savedIds' ? [] : pageOf([]));
    renderApp('/recipes/r-1', queryClient);
    await userEvent.click(await screen.findByRole('button', { name: 'Remove' }));
    await userEvent.type(await screen.findByRole('textbox', { name: 'Reason' }), 'Gone already');
    await userEvent.click(screen.getByRole('button', { name: 'Remove recipe' }));
    expect(await screen.findByText('This recipe was already removed.')).toBeInTheDocument();
    expect(await screen.findByText('Home Marker')).toBeInTheDocument();
    for (const key of keys) expect(queryClient.getQueryState(key)?.isInvalidated).toBe(true);
    expect(queryClient.getQueryData(['recipe', 'r-1'])).toBeUndefined();
    expect(deletePhotos).not.toHaveBeenCalled();
    expect(deleteVideoFiles).not.toHaveBeenCalled();
  });

  test('a 403 for missing two-factor setup says how to fix it', async () => {
    stubPage({ me: admin });
    server.use(
      http.post(`${BASE}/api/admin/recipes/r-1/remove`, () =>
        HttpResponse.json({ status: 403, detail: 'Forbidden', code: 'admin_mfa_setup_required' }, { status: 403 }),
      ),
    );
    renderApp('/recipes/r-1');
    await userEvent.click(await screen.findByRole('button', { name: 'Remove' }));
    await userEvent.type(await screen.findByRole('textbox', { name: 'Reason' }), 'Spam');
    await userEvent.click(screen.getByRole('button', { name: 'Remove recipe' }));
    expect(await screen.findByText('Set up two-factor authentication to remove content.')).toBeInTheDocument();
  });
});

describe('removing a comment', () => {
  const others = commentFixture({ id: 'c-2', user: author, body: 'Not great' });
  const mine = commentFixture({ id: 'c-1', user: admin, body: 'My own words' });

  test("an admin sees Remove on other people's comments only; their own keep Delete", async () => {
    stubPage({ me: admin, comments: [mine, others] });
    renderApp('/recipes/r-1');
    const theirs = (await screen.findByText('Not great')).closest('li')!;
    const own = screen.getByText('My own words').closest('li')!;
    expect(within(theirs).getByRole('button', { name: /remove comment by/i })).toBeInTheDocument();
    expect(within(own).queryByRole('button', { name: /remove/i })).not.toBeInTheDocument();
    expect(within(own).getByRole('button', { name: /delete/i })).toBeInTheDocument();
  });

  test('an admin who owns the recipe sees only Remove on a stranger, and Delete on their own comment', async () => {
    stubPage({ me: admin, recipe: recipeFixture({ user: admin }), comments: [mine, others] });
    renderApp('/recipes/r-1');
    const theirs = (await screen.findByText('Not great')).closest('li')!;
    const own = screen.getByText('My own words').closest('li')!;
    expect(within(theirs).getByRole('button', { name: /remove comment by/i })).toBeInTheDocument();
    expect(within(theirs).queryByRole('button', { name: /delete/i })).not.toBeInTheDocument();
    expect(within(own).getByRole('button', { name: /delete/i })).toBeInTheDocument();
    expect(within(own).queryByRole('button', { name: /remove/i })).not.toBeInTheDocument();
  });

  test('a non-admin never sees Remove', async () => {
    stubPage({ comments: [mine, others] });
    renderApp('/recipes/r-1');
    await screen.findByText('Not great');
    expect(screen.queryByRole('button', { name: /remove/i })).not.toBeInTheDocument();
  });

  test('the dialog needs a reason, posts it, toasts, and the comment disappears after the refetch', async () => {
    let removed = false;
    stubPage({ me: admin });
    server.use(
      http.get(`${BASE}/api/recipes/r-1/comments`, () =>
        HttpResponse.json(pageOf(removed ? [mine] : [mine, others])),
      ),
    );
    let body: unknown = null;
    server.use(
      http.post(`${BASE}/api/admin/comments/c-2/remove`, async ({ request }) => {
        body = await request.json();
        removed = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderApp('/recipes/r-1');
    const item = (await screen.findByText('Not great')).closest('li')!;
    await userEvent.click(within(item).getByRole('button', { name: /remove comment by/i }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Remove this comment?')).toBeInTheDocument();
    expect(within(dialog).getByText('The author will get an email with your reason.')).toBeInTheDocument();
    const confirm = within(dialog).getByRole('button', { name: 'Remove comment' });
    expect(confirm).toBeDisabled();
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Reason' }), 'Abusive');
    expectNoEmoji();
    await userEvent.click(confirm);

    expect(await screen.findByText('Comment removed')).toBeInTheDocument();
    expect(body).toEqual({ reason: 'Abusive' });
    await waitFor(() => expect(screen.queryByText('Not great')).not.toBeInTheDocument());
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  test('a 400 shows the reason error in the dialog', async () => {
    stubPage({ me: admin, comments: [others] });
    server.use(
      http.post(`${BASE}/api/admin/comments/c-2/remove`, () =>
        HttpResponse.json(
          { status: 400, detail: 'Validation failed', errors: { reason: 'Reason must be 500 characters or fewer' } },
          { status: 400 },
        ),
      ),
    );
    renderApp('/recipes/r-1');
    const item = (await screen.findByText('Not great')).closest('li')!;
    await userEvent.click(within(item).getByRole('button', { name: /remove comment by/i }));
    await userEvent.type(await screen.findByRole('textbox', { name: 'Reason' }), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Remove comment' }));
    expect(await within(await screen.findByRole('dialog')).findByRole('alert')).toHaveTextContent(
      'Reason must be 500 characters or fewer',
    );
  });

  test('the reason is trimmed before it is sent', async () => {
    stubPage({ me: admin, comments: [others] });
    let body: unknown = null;
    server.use(
      http.post(`${BASE}/api/admin/comments/c-2/remove`, async ({ request }) => {
        body = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderApp('/recipes/r-1');
    const item = (await screen.findByText('Not great')).closest('li')!;
    await userEvent.click(within(item).getByRole('button', { name: /remove comment by/i }));
    await userEvent.type(await screen.findByRole('textbox', { name: 'Reason' }), '   Abusive   ');
    await userEvent.click(screen.getByRole('button', { name: 'Remove comment' }));
    expect(await screen.findByText('Comment removed')).toBeInTheDocument();
    expect(body).toEqual({ reason: 'Abusive' });
  });

  test('a 404 says it was already removed, closes the dialog and refetches', async () => {
    let gone = false;
    stubPage({ me: admin });
    server.use(
      http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf(gone ? [] : [others]))),
      http.post(`${BASE}/api/admin/comments/c-2/remove`, () => {
        gone = true;
        return HttpResponse.json({ status: 404, detail: 'Comment not found' }, { status: 404 });
      }),
    );
    renderApp('/recipes/r-1');
    const item = (await screen.findByText('Not great')).closest('li')!;
    await userEvent.click(within(item).getByRole('button', { name: /remove comment by/i }));
    await userEvent.type(await screen.findByRole('textbox', { name: 'Reason' }), 'Spam');
    await userEvent.click(screen.getByRole('button', { name: 'Remove comment' }));
    expect(await screen.findByText('This comment was already removed.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('Not great')).not.toBeInTheDocument());
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  test('a 403 for missing two-factor setup says how to fix it', async () => {
    stubPage({ me: admin, comments: [others] });
    server.use(
      http.post(`${BASE}/api/admin/comments/c-2/remove`, () =>
        HttpResponse.json({ status: 403, detail: 'Forbidden', code: 'admin_mfa_setup_required' }, { status: 403 }),
      ),
    );
    renderApp('/recipes/r-1');
    const item = (await screen.findByText('Not great')).closest('li')!;
    await userEvent.click(within(item).getByRole('button', { name: /remove comment by/i }));
    await userEvent.type(await screen.findByRole('textbox', { name: 'Reason' }), 'Spam');
    await userEvent.click(screen.getByRole('button', { name: 'Remove comment' }));
    expect(await screen.findByText('Set up two-factor authentication to remove content.')).toBeInTheDocument();
  });
});
