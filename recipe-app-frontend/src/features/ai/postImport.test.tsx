import type { MockInstance } from 'vitest';
import { http, HttpResponse, delay } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { profileFixture } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import { shrinkPhoto, PhotoUnreadableError } from '@/lib/shrinkPhoto';
import type { AiUsage, GeneratedRecipe } from '@/lib/types';

const BASE = 'http://localhost:8080';
const IMPORT = `${BASE}/api/users/me/ai-recipes/import`;

const mocks = vi.hoisted(() => ({ session: null as unknown }));

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({ data: { session: mocks.session } }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
      signOut: async () => {},
    },
  },
}));

// The real shrinking needs a canvas, which jsdom lacks; the panel only cares that it returns a blob.
vi.mock('@/lib/shrinkPhoto', async (importActual) => ({
  ...(await importActual<typeof import('@/lib/shrinkPhoto')>()),
  shrinkPhoto: vi.fn(async (file: Blob) => file),
}));

function postRecipe(overrides: Partial<GeneratedRecipe> = {}): GeneratedRecipe {
  return {
    id: 'p-1', kind: 'post', mealType: null, title: 'Charred Corn and Feta Salad',
    description: 'Sweet corn, salty feta and lime.', servings: 2, prepMinutes: 10, cookMinutes: 10,
    cuisine: '',
    ingredients: [{ name: 'corn', amount: '2 cobs', extra: false }, { name: 'feta', amount: '100 g', extra: false }],
    steps: ['Char the corn.', 'Toss with the feta and lime.'],
    diets: { vegetarian: true, vegan: false, glutenFree: true, dairyFree: false },
    allergens: [], saved: false, createdAt: '2026-11-01T10:00:00Z', provider: 'groq',
    origin: 'recipe', swaps: [],
    ...overrides,
  };
}

const USAGE: AiUsage = { used: 3, limit: 10, remaining: 7, scansUsed: 0, scanLimit: 10, scansRemaining: 10 };

function signedIn(usage: AiUsage = USAGE) {
  mocks.session = { access_token: 't', user: { id: 'u-1', email: 'jane@example.com' } };
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/users/me/ai-recipes/usage`, () => HttpResponse.json(usage)),
    http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json([])),
    http.get(`${BASE}/api/ingredients/suggest`, () => HttpResponse.json([])),
  );
}

function importReturns(recipe: GeneratedRecipe = postRecipe(), remainingToday = 6) {
  server.use(http.post(IMPORT, () => HttpResponse.json({ recipe, remainingToday })));
}

const jpeg = (name: string) => new File([name], `${name}.jpg`, { type: 'image/jpeg' });

let urlCounter = 0;
let createUrl: ReturnType<typeof vi.fn>;
let revokeUrl: ReturnType<typeof vi.fn>;
const realCreate = URL.createObjectURL;
const realRevoke = URL.revokeObjectURL;

beforeEach(() => {
  urlCounter = 0;
  createUrl = vi.fn(() => `blob:photo-${++urlCounter}`);
  revokeUrl = vi.fn();
  URL.createObjectURL = createUrl as unknown as typeof URL.createObjectURL;
  URL.revokeObjectURL = revokeUrl as unknown as typeof URL.revokeObjectURL;
  vi.mocked(shrinkPhoto).mockClear();
  vi.mocked(shrinkPhoto).mockImplementation(async (file: Blob) => file);
});

afterEach(() => {
  URL.createObjectURL = realCreate;
  URL.revokeObjectURL = realRevoke;
  vi.restoreAllMocks();
});

const captionBox = () => screen.getByRole('textbox', { name: "Paste the post's caption" });
const makeButton = () => screen.getByRole('button', { name: 'Make recipe' });
const postPanel = () => document.getElementById('cook-panel-post') as HTMLElement;

async function openPost() {
  const user = userEvent.setup();
  const view = renderApp('/cook?tab=post');
  await screen.findByRole('textbox', { name: "Paste the post's caption" });
  const input = postPanel().querySelector('input[type="file"]') as HTMLInputElement;
  return { user, input, view };
}

async function pick(user: ReturnType<typeof userEvent.setup>, input: HTMLInputElement, ...names: string[]) {
  await user.upload(input, names.map(jpeg));
}

/** The FormData bodies sent to the import endpoint, in order. */
function importForms(fetchSpy: MockInstance<typeof fetch>) {
  return fetchSpy.mock.calls
    .filter(([url]) => String(url) === IMPORT)
    .map(([, init]) => init?.body as FormData);
}

describe('PostImport on /cook?tab=post', () => {
  it('shows the panel copy', async () => {
    signedIn();
    const { input } = await openPost();

    expect(screen.getByRole('button', { name: 'Add screenshots' })).toBeEnabled();
    expect(input).toHaveAttribute('accept', 'image/jpeg,image/png,image/webp,image/heic,image/heif');
    expect(input).toHaveAttribute('multiple');
    expect(input).not.toHaveAttribute('capture');
    expect(screen.getByText(
      "Tip: a screenshot of the recipe slide works best. Open the full caption (tap 'more') before you copy it.",
    )).toBeInTheDocument();
    expect(screen.getByText(
      'Screenshots go to Groq only and are never stored. The caption may go to Groq or Google Gemini. '
      + 'Nothing about the post is kept.',
    )).toBeInTheDocument();
    expect(await within(postPanel()).findByText('7 left today')).toBeInTheDocument();
    expectNoEmoji();
  });

  it('opens the hidden file input from Add screenshots', async () => {
    signedIn();
    const { user, input } = await openPost();
    const click = vi.spyOn(input, 'click');
    await user.click(screen.getByRole('button', { name: 'Add screenshots' }));
    expect(click).toHaveBeenCalledTimes(1);
  });

  it('counter_showsLength', async () => {
    signedIn();
    const { user } = await openPost();
    expect(screen.getByText('0 / 2,200')).toBeInTheDocument();

    await user.type(captionBox(), 'Corn salad');

    expect(screen.getByText('10 / 2,200')).toBeInTheDocument();
    expect(screen.queryByText('Shorten the caption to 2,200 characters.')).not.toBeInTheDocument();
  });

  it('pastingOverTheLimit_keepsAllTextAndBlocks', async () => {
    signedIn();
    const { user } = await openPost();
    // 100 emojis (2 UTF-16 units each), line breaks and plain text: 2,300 units in all.
    const text = '\u{1F33D}'.repeat(100) + '\n'.repeat(50) + 'a'.repeat(2050);
    expect(text).toHaveLength(2300);

    await user.click(captionBox());
    await user.paste(text);

    expect(captionBox()).toHaveValue(text);
    expect((captionBox() as HTMLTextAreaElement).value).toHaveLength(2300);
    expect(captionBox()).not.toHaveAttribute('maxlength');
    expect(screen.getByText('2,300 / 2,200')).toHaveClass('text-danger');
    expect(screen.getByText('Shorten the caption to 2,200 characters.')).toBeInTheDocument();
    expect(makeButton()).toBeDisabled();

    await user.type(captionBox(), '{Backspace}'.repeat(100));
    expect(screen.getByText('2,200 / 2,200')).not.toHaveClass('text-danger');
    expect(screen.queryByText('Shorten the caption to 2,200 characters.')).not.toBeInTheDocument();
    expect(makeButton()).toBeEnabled();
  });

  it('makeRecipe_disabledUntilCaptionOrScreenshot', async () => {
    signedIn();
    const { user, input } = await openPost();
    expect(makeButton()).toBeDisabled();

    await user.type(captionBox(), '   ');
    expect(makeButton()).toBeDisabled();

    await user.type(captionBox(), 'Corn');
    expect(makeButton()).toBeEnabled();

    await user.clear(captionBox());
    expect(makeButton()).toBeDisabled();

    await pick(user, input, 'a');
    expect(makeButton()).toBeEnabled();
  });

  it('pickingThree_showsMessageKeepsTwo', async () => {
    signedIn();
    const { user, input } = await openPost();
    await pick(user, input, 'a', 'b', 'c');

    expect(screen.getAllByRole('img', { name: /^Screenshot \d$/ })).toHaveLength(2);
    expect(screen.getByText('Choose up to 2 screenshots.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove screenshot 3' })).not.toBeInTheDocument();
  });

  it('removeScreenshot2_works', async () => {
    signedIn();
    const { user, input } = await openPost();
    await pick(user, input, 'a', 'b');

    await user.click(screen.getByRole('button', { name: 'Remove screenshot 2' }));

    expect(revokeUrl).toHaveBeenCalledTimes(1);
    expect(revokeUrl).toHaveBeenCalledWith('blob:photo-2');
    expect(screen.getAllByRole('img', { name: /^Screenshot \d$/ }).map((i) => i.getAttribute('src')))
      .toEqual(['blob:photo-1']);
    expect(screen.queryByRole('button', { name: 'Remove screenshot 2' })).not.toBeInTheDocument();
  });

  it('revokes the screenshots left when the page unmounts', async () => {
    signedIn();
    const { user, input, view } = await openPost();
    await pick(user, input, 'a', 'b');

    view.unmount();

    expect(revokeUrl).toHaveBeenCalledWith('blob:photo-1');
    expect(revokeUrl).toHaveBeenCalledWith('blob:photo-2');
  });

  it('request_isFormDataWithCaptionAndScreenshots', async () => {
    signedIn();
    importReturns();
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const { user, input } = await openPost();
    await user.type(captionBox(), '  Charred corn salad  ');
    await pick(user, input, 'a', 'b');
    await user.click(makeButton());
    await screen.findByRole('heading', { name: 'Charred Corn and Feta Salad' });

    const [form] = importForms(fetchSpy);
    expect(form).toBeInstanceOf(FormData);
    expect(form.get('caption')).toBe('Charred corn salad');
    const parts = form.getAll('screenshots') as File[];
    expect(parts.map((f) => f.name)).toEqual(['screenshot-1.jpg', 'screenshot-2.jpg']);
    expect(shrinkPhoto).toHaveBeenCalledTimes(2);
  });

  it('a whitespace caption with a screenshot sends no caption part', async () => {
    signedIn();
    importReturns();
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const { user, input } = await openPost();
    await user.type(captionBox(), '   ');
    await pick(user, input, 'a');
    await user.click(makeButton());
    await screen.findByRole('heading', { name: 'Charred Corn and Feta Salad' });

    const [form] = importForms(fetchSpy);
    expect(form.has('caption')).toBe(false);
    expect(form.getAll('screenshots')).toHaveLength(1);
  });

  it('waitingStatus_inMountedRegion', async () => {
    signedIn();
    server.use(http.post(IMPORT, async () => {
      await delay(150);
      return HttpResponse.json({ recipe: postRecipe(), remainingToday: 6 });
    }));
    const { user } = await openPost();
    const statusBefore = screen.getByRole('status');
    expect(statusBefore).toBeEmptyDOMElement();
    expect(statusBefore).toHaveAttribute('aria-live', 'polite');

    await user.type(captionBox(), 'Charred corn salad');
    await user.click(makeButton());

    const status = await screen.findByRole('status');
    expect(status).toBe(statusBefore);
    expect(status).toHaveTextContent('Reading the post and writing your recipe. This can take up to a minute.');
    expect(makeButton()).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Add screenshots' })).toBeDisabled();
    expectNoEmoji();

    await screen.findByRole('heading', { name: 'Charred Corn and Feta Salad' });
    expect(screen.getByRole('status')).toBeEmptyDOMElement();
    expect(makeButton()).toBeEnabled();
  });

  it.each([
    ['no_dish', 422, "Couldn't find a dish in this post. Try a screenshot that shows the food or the recipe."],
    ['invalid_post', 400, 'Add a caption (up to 2,200 characters) or 1 to 2 screenshots.'],
    ['invalid_photo', 400, 'Use 1 or 2 screenshots (JPEG, up to 1 MB each).'],
    ['daily_limit', 429, "You've used today's 10 AI recipes. More tomorrow."],
    ['ai_quota', 503, 'The free AI limit is reached for now. Try again later.'],
  ])('shows the message for %s', async (code, status, message) => {
    signedIn();
    server.use(http.post(IMPORT, () => HttpResponse.json({ detail: 'server detail', code }, { status })));
    const { user, input } = await openPost();
    await pick(user, input, 'a');
    await user.click(makeButton());

    expect(await screen.findByRole('alert')).toHaveTextContent(message);
    if (code === 'daily_limit') {
      expect(makeButton()).toBeDisabled();
      expect(within(postPanel()).getByText('0 left today')).toBeInTheDocument();
    } else {
      expect(makeButton()).toBeEnabled();
    }
    expectNoEmoji();
  });

  it('shrinkFailure_showsPhotoUnreadable', async () => {
    signedIn();
    vi.mocked(shrinkPhoto).mockRejectedValueOnce(new PhotoUnreadableError());
    const { user, input } = await openPost();
    await pick(user, input, 'a');
    await user.click(makeButton());

    expect(await screen.findByRole('alert')).toHaveTextContent(
      "This photo type can't be opened here. Try a JPEG or take a new photo.",
    );
    expect(screen.getByRole('button', { name: 'Remove screenshot 1' })).toBeInTheDocument();
  });

  it('shrinks one screenshot at a time', async () => {
    signedIn();
    importReturns();
    const releases: Array<() => void> = [];
    vi.mocked(shrinkPhoto).mockImplementation((file: Blob) => new Promise<Blob>((resolve) => {
      releases.push(() => resolve(file));
    }));
    const { user, input } = await openPost();
    await pick(user, input, 'a', 'b');
    await user.click(makeButton());

    await waitFor(() => expect(shrinkPhoto).toHaveBeenCalledTimes(1));
    await new Promise((r) => setTimeout(r, 20));
    expect(shrinkPhoto).toHaveBeenCalledTimes(1);
    releases[0]();
    await waitFor(() => expect(shrinkPhoto).toHaveBeenCalledTimes(2));
    releases[1]();
    await screen.findByRole('heading', { name: 'Charred Corn and Feta Salad' });
  });

  it('generateAnother_resendsSameParts', async () => {
    signedIn();
    let n = 0;
    server.use(http.post(IMPORT, () => {
      n += 1;
      return HttpResponse.json({ recipe: postRecipe({ id: `p-${n}`, title: `Corn Salad ${n}` }), remainingToday: 7 - n });
    }));
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const { user, input } = await openPost();
    await user.type(captionBox(), 'Charred corn salad');
    await pick(user, input, 'a', 'b');
    await user.click(makeButton());
    await screen.findByRole('heading', { name: 'Corn Salad 1' });

    // Edits after the first result do not change what Generate another sends.
    await user.type(captionBox(), ' with lime');
    await user.click(screen.getByRole('button', { name: 'Remove screenshot 2' }));
    await user.click(screen.getByRole('button', { name: 'Generate another' }));
    await screen.findByRole('heading', { name: 'Corn Salad 2' });

    const forms = importForms(fetchSpy);
    expect(forms).toHaveLength(2);
    const summary = async (form: FormData) => ({
      caption: form.get('caption'),
      screenshots: await Promise.all((form.getAll('screenshots') as File[]).map((f) => f.text())),
    });
    expect(await summary(forms[0])).toEqual({ caption: 'Charred corn salad', screenshots: ['a', 'b'] });
    expect(await summary(forms[1])).toEqual(await summary(forms[0]));
    expect(shrinkPhoto).toHaveBeenCalledTimes(2); // the shrunk screenshots are reused, not shrunk again
    expect(within(postPanel()).getByText('5 left today')).toBeInTheDocument();
  });

  it('links Open to the recipe page', async () => {
    signedIn();
    importReturns();
    const { user } = await openPost();
    await user.type(captionBox(), 'Charred corn salad');
    await user.click(makeButton());
    await screen.findByRole('heading', { name: 'Charred Corn and Feta Salad' });

    expect(screen.getByRole('link', { name: 'Open' })).toHaveAttribute('href', '/ai-recipes/p-1');
    expect(screen.getByText('Based on an Instagram post')).toBeInTheDocument();
    expectNoEmoji();
  });

  it('screenshotsKeptAfterSuccessAndFailure', async () => {
    signedIn();
    importReturns();
    const { user, input } = await openPost();
    await pick(user, input, 'a', 'b');
    await user.click(makeButton());
    await screen.findByRole('heading', { name: 'Charred Corn and Feta Salad' });
    expect(screen.getAllByRole('img', { name: /^Screenshot \d$/ })).toHaveLength(2);

    server.use(http.post(IMPORT, () => HttpResponse.json({ detail: 'x', code: 'no_dish' }, { status: 422 })));
    await user.click(makeButton());
    await screen.findByRole('alert');
    expect(screen.getAllByRole('img', { name: /^Screenshot \d$/ })).toHaveLength(2);
    expect(revokeUrl).not.toHaveBeenCalled();
    expect(makeButton()).toBeEnabled();
  });

  it('switchingTabsMidImport_keepsResultAndScreenshots', async () => {
    signedIn();
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => { release = resolve; });
    server.use(http.post(IMPORT, async () => {
      await gate;
      return HttpResponse.json({ recipe: postRecipe(), remainingToday: 6 });
    }));
    const { user, input } = await openPost();
    await user.type(captionBox(), 'Charred corn salad');
    await pick(user, input, 'a');
    await user.click(makeButton());
    await screen.findByText('Reading the post and writing your recipe. This can take up to a minute.');

    await user.click(screen.getByRole('tab', { name: 'From my ingredients' }));
    expect(screen.queryByRole('textbox', { name: "Paste the post's caption" })).not.toBeInTheDocument();
    release();
    await waitFor(() => expect(within(screen.getByRole('tabpanel')).getByText('6 left today')).toBeInTheDocument());

    await user.click(screen.getByRole('tab', { name: 'From a post' }));
    expect(await screen.findByRole('heading', { name: 'Charred Corn and Feta Salad' })).toBeInTheDocument();
    expect(screen.getAllByRole('img', { name: /^Screenshot \d$/ })).toHaveLength(1);
    expect(captionBox()).toHaveValue('Charred corn salad');
    expect(revokeUrl).not.toHaveBeenCalled();
  });
});
