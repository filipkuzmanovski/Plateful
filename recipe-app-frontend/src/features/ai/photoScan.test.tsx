import { http, HttpResponse, delay } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { profileFixture } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import { shrinkPhoto, PhotoUnreadableError } from '@/lib/shrinkPhoto';
import { leftOutLine, mergeIngredients } from './mergeIngredients';
import type { AiUsage, ScanResult } from '@/lib/types';

const BASE = 'http://localhost:8080';
const SCAN = `${BASE}/api/users/me/ai-recipes/scan`;
const GENERATE = `${BASE}/api/users/me/ai-recipes/generate`;

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

function scanReturns(result: Partial<ScanResult> = {}) {
  server.use(http.post(SCAN, () => HttpResponse.json({ ingredients: [], leftOut: [], remainingScans: 9, ...result })));
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
  vi.mocked(shrinkPhoto).mockImplementation(async (file: Blob) => file);
});

afterEach(() => {
  URL.createObjectURL = realCreate;
  URL.revokeObjectURL = realRevoke;
  vi.restoreAllMocks();
});

async function openCook() {
  const user = userEvent.setup();
  const view = renderApp('/cook');
  await screen.findByRole('heading', { name: 'From a photo' });
  const input = view.container.querySelector('input[type="file"]') as HTMLInputElement;
  return { user, input, view };
}

async function pick(user: ReturnType<typeof userEvent.setup>, input: HTMLInputElement, ...names: string[]) {
  await user.upload(input, names.map(jpeg));
}

async function addChips(user: ReturnType<typeof userEvent.setup>, ...names: string[]) {
  const field = await screen.findByLabelText('Ingredients');
  for (const n of names) await user.type(field, `${n}{Enter}`);
}

const scanButton = () => screen.getByRole('button', { name: 'Scan photos' });

describe('mergeIngredients', () => {
  it('de-duplicates case-insensitively and keeps current order, then found order', () => {
    expect(mergeIngredients(['Rice', 'egg'], ['EGG', 'tomato', 'rice', ' Basil '], 15)).toEqual({
      merged: ['Rice', 'egg', 'tomato', 'Basil'],
      didntFit: [],
    });
  });

  it('caps at max and reports what did not fit, in found order', () => {
    expect(mergeIngredients(['a', 'b'], ['c', 'd', 'e', 'f'], 4)).toEqual({
      merged: ['a', 'b', 'c', 'd'],
      didntFit: ['e', 'f'],
    });
  });

  it('adds nothing when the chips are already full and reports every found name', () => {
    const current = Array.from({ length: 15 }, (_, i) => `item${i}`);
    expect(mergeIngredients(current, ['olive', 'leek'], 15)).toEqual({ merged: current, didntFit: ['olive', 'leek'] });
  });

  it('skips blank names', () => {
    expect(mergeIngredients([], ['  ', 'egg'], 15)).toEqual({ merged: ['egg'], didntFit: [] });
  });
});

describe('leftOutLine', () => {
  it('is null when nothing was left out', () => {
    expect(leftOutLine([])).toBeNull();
  });

  it('groups reasons per ingredient and labels them', () => {
    expect(leftOutLine([
      { ingredient: 'peanut butter', reason: 'allergy: Peanuts' },
      { ingredient: 'mushroom', reason: 'dislike' },
      { ingredient: 'peanut butter', reason: 'diet: Vegan' },
    ])).toBe('Left out because of your food preferences: peanut butter (Peanuts, Vegan), mushroom (you dislike it).');
  });

  it('shows a dislike written with a colon the same way', () => {
    expect(leftOutLine([{ ingredient: 'kale', reason: 'dislike: kale' }]))
      .toBe('Left out because of your food preferences: kale (you dislike it).');
  });
});

describe('PhotoScan on /cook', () => {
  it('shows the panel copy and starts with Scan disabled', async () => {
    signedIn();
    await openCook();
    expect(scanButton()).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Take or choose photos' })).toBeEnabled();
    expect(screen.getByText(
      'Photos go to Groq only to spot ingredients and are never stored. Keep people and papers out of the shot.',
    )).toBeInTheDocument();
    expect(screen.queryByText('Choose up to 3 photos.')).not.toBeInTheDocument();
  });

  it('opens the hidden file input from the button, without capture', async () => {
    signedIn();
    const { user, input } = await openCook();
    expect(input).toHaveAttribute('accept', 'image/jpeg,image/png,image/webp,image/heic,image/heif');
    expect(input).toHaveAttribute('multiple');
    expect(input).not.toHaveAttribute('capture');
    const click = vi.spyOn(input, 'click');
    await user.click(screen.getByRole('button', { name: 'Take or choose photos' }));
    expect(click).toHaveBeenCalledTimes(1);
  });

  it('pickingFour_keepsThreeAndSaysSo', async () => {
    signedIn();
    const { user, input } = await openCook();
    await pick(user, input, 'a', 'b', 'c', 'd');

    expect(screen.getAllByRole('img', { name: /^Photo \d$/ })).toHaveLength(3);
    expect(screen.getByText('Choose up to 3 photos.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove photo 4' })).not.toBeInTheDocument();
  });

  it('removePhoto2_removesTheSecondThumbnail', async () => {
    signedIn();
    const { user, input } = await openCook();
    await pick(user, input, 'a', 'b', 'c');
    expect(createUrl).toHaveBeenCalledTimes(3);

    await user.click(screen.getByRole('button', { name: 'Remove photo 2' }));

    expect(revokeUrl).toHaveBeenCalledTimes(1);
    expect(revokeUrl).toHaveBeenCalledWith('blob:photo-2');
    const imgs = screen.getAllByRole('img', { name: /^Photo \d$/ });
    expect(imgs.map((i) => i.getAttribute('src'))).toEqual(['blob:photo-1', 'blob:photo-3']);
    expect(screen.queryByRole('button', { name: 'Remove photo 3' })).not.toBeInTheDocument();
  });

  it('revokes the thumbnails left when the page unmounts', async () => {
    signedIn();
    const { user, input, view } = await openCook();
    await pick(user, input, 'a', 'b');
    expect(revokeUrl).not.toHaveBeenCalled();

    view.unmount();

    expect(revokeUrl).toHaveBeenCalledTimes(2);
    expect(revokeUrl).toHaveBeenCalledWith('blob:photo-1');
    expect(revokeUrl).toHaveBeenCalledWith('blob:photo-2');
  });

  it('scan_sendsFormDataWithPhotosParts', async () => {
    signedIn();
    scanReturns({ ingredients: ['egg'] });
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const { user, input } = await openCook();
    await pick(user, input, 'a', 'b');
    await user.click(scanButton());
    await screen.findByText(/^Found 1 ingredient\./);

    const call = fetchSpy.mock.calls.find(([url]) => String(url) === SCAN);
    expect(call).toBeDefined();
    const form = call![1]?.body as FormData;
    expect(form).toBeInstanceOf(FormData);
    expect(form.getAll('photos')).toHaveLength(2);
    expect(shrinkPhoto).toHaveBeenCalledTimes(2);
  });

  it('scan_showsStatusWhileWaiting', async () => {
    signedIn();
    server.use(http.post(SCAN, async () => {
      await delay(150);
      return HttpResponse.json({ ingredients: ['egg'], leftOut: [], remainingScans: 9 });
    }));
    const { user, input } = await openCook();
    const statusBefore = screen.getByRole('status');
    expect(statusBefore).toBeEmptyDOMElement();
    expect(statusBefore).toHaveAttribute('aria-live', 'polite');
    await pick(user, input, 'a');
    await user.click(scanButton());

    const status = await screen.findByRole('status');
    expect(status).toBe(statusBefore); // the same element, not a new one mounted with its text
    expect(status).toHaveTextContent('Looking at your photos. This can take up to 30 seconds.');
    expect(scanButton()).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Take or choose photos' })).toBeDisabled();
    await waitFor(() => expect(screen.queryByText(/Looking at your photos/)).not.toBeInTheDocument());
  });

  it('scan_addsToExistingChips_andSaysFound', async () => {
    signedIn();
    scanReturns({ ingredients: ['egg', 'Rice'] });
    const { user, input } = await openCook();
    await addChips(user, 'rice');
    await pick(user, input, 'a');
    await user.click(scanButton());

    expect(await screen.findByText(
      'Found 2 ingredients. Check them: remove anything wrong and add anything missed.',
    )).toBeInTheDocument();
    const chips = screen.getAllByRole('button', { name: /^Remove (rice|egg)$/i });
    expect(chips.map((b) => b.getAttribute('aria-label'))).toEqual(['Remove rice', 'Remove egg']);
  });

  it('says "1 ingredient" for a single find', async () => {
    signedIn();
    scanReturns({ ingredients: ['egg'] });
    const { user, input } = await openCook();
    await pick(user, input, 'a');
    await user.click(scanButton());
    expect(await screen.findByText(
      'Found 1 ingredient. Check them: remove anything wrong and add anything missed.',
    )).toBeInTheDocument();
  });

  it('scan_reportsDidntFit', async () => {
    signedIn();
    scanReturns({ ingredients: ['olive', 'leek', 'kale'] });
    const { user, input } = await openCook();
    await addChips(user, ...Array.from({ length: 13 }, (_, i) => `item${i}`));
    await pick(user, input, 'a');
    await user.click(scanButton());

    expect(await screen.findByText("1 more didn't fit (15 max): kale.")).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove olive' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove leek' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove kale' })).not.toBeInTheDocument();
  });

  it('scanTwice_withChipsAt15_addsNothingAndLosesNoChip', async () => {
    signedIn();
    scanReturns({ ingredients: ['olive', 'leek'] });
    const { user, input } = await openCook();
    await addChips(user, ...Array.from({ length: 15 }, (_, i) => `item${i}`));
    await pick(user, input, 'a');
    await user.click(scanButton());
    expect(await screen.findByText("2 more didn't fit (15 max): olive, leek.")).toBeInTheDocument();

    await pick(user, input, 'b');
    await user.click(scanButton());
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Remove photo 1' })).not.toBeInTheDocument());
    expect(screen.getByText("2 more didn't fit (15 max): olive, leek.")).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /^Remove item\d+$/ })).toHaveLength(15);
    expect(screen.queryByRole('button', { name: 'Remove olive' })).not.toBeInTheDocument();
  });

  it('scan_reportsLeftOutGrouped', async () => {
    signedIn();
    scanReturns({
      ingredients: ['egg'],
      leftOut: [
        { ingredient: 'peanut butter', reason: 'allergy: Peanuts' },
        { ingredient: 'mushroom', reason: 'dislike' },
        { ingredient: 'peanut butter', reason: 'diet: Vegan' },
      ],
    });
    const { user, input } = await openCook();
    await pick(user, input, 'a');
    await user.click(scanButton());

    expect(await screen.findByText(
      'Left out because of your food preferences: peanut butter (Peanuts, Vegan), mushroom (you dislike it).',
    )).toBeInTheDocument();
  });

  it('scan_emptyResult_showsNoFoodSpotted', async () => {
    signedIn();
    scanReturns({ ingredients: [], remainingScans: 8 });
    const { user, input } = await openCook();
    await pick(user, input, 'a');
    await user.click(scanButton());

    expect(await screen.findByText(
      'No food spotted. Try a closer, brighter photo, or type your ingredients.',
    )).toBeInTheDocument();
    expect(screen.queryByText(/^Found/)).not.toBeInTheDocument();
    expect(screen.getByText('8 scans left today')).toBeInTheDocument();
  });

  it.each([
    ['invalid_photo', 400, 'Use 1 to 3 photos (JPEG, up to 1 MB each).'],
    ['scan_failed', 422, "Couldn't read the photo. Try again or type your ingredients."],
    ['ai_quota', 503, 'The free AI limit is reached for now. Try again later.'],
    ['ai_unavailable', 503, "The AI service isn't responding. Try again in a minute."],
    ['no_preferences', 409, 'Set your food preferences first.'],
  ])('scan_errorsShowTheirMessage: %s', async (code, status, message) => {
    signedIn();
    server.use(http.post(SCAN, () => HttpResponse.json({ detail: 'server detail', code }, { status })));
    const { user, input } = await openCook();
    await pick(user, input, 'a');
    await user.click(scanButton());

    expect(await screen.findByRole('alert')).toHaveTextContent(message);
    // The photos stay so the user can try again.
    expect(screen.getByRole('button', { name: 'Remove photo 1' })).toBeInTheDocument();
    expect(scanButton()).toBeEnabled();
  });

  it('scan_limit_disablesScan', async () => {
    signedIn();
    server.use(http.post(SCAN, () => HttpResponse.json({ detail: 'x', code: 'scan_limit' }, { status: 429 })));
    const { user, input } = await openCook();
    await pick(user, input, 'a');
    await user.click(scanButton());

    expect(await screen.findByRole('alert')).toHaveTextContent("You've used today's 10 photo scans. More tomorrow.");
    expect(scanButton()).toBeDisabled();
    expect(screen.getByText('0 scans left today')).toBeInTheDocument();
  });

  it('disables Scan when usage already says 0 scans left', async () => {
    signedIn({ ...USAGE, scansUsed: 10, scansRemaining: 0 });
    const { user, input } = await openCook();
    await pick(user, input, 'a');
    expect(await screen.findByText('0 scans left today')).toBeInTheDocument();
    expect(scanButton()).toBeDisabled();
  });

  it('scansLeft_fromUsage_thenFromResponse', async () => {
    signedIn();
    scanReturns({ ingredients: ['egg'], remainingScans: 7 });
    const { user, input } = await openCook();
    expect(await screen.findByText('10 scans left today')).toBeInTheDocument();
    await pick(user, input, 'a');
    await user.click(scanButton());
    expect(await screen.findByText('7 scans left today')).toBeInTheDocument();
  });

  it('afterScan_generateSendsTheMergedChips', async () => {
    signedIn();
    scanReturns({ ingredients: ['egg', 'Rice'] });
    let body: unknown = null;
    server.use(http.post(GENERATE, async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ detail: 'stop', code: 'generation_failed' }, { status: 422 });
    }));
    const { user, input } = await openCook();
    await addChips(user, 'rice');
    await pick(user, input, 'a');
    await user.click(scanButton());
    await screen.findByText(/^Found 2 ingredients\./);

    await user.click(screen.getByRole('button', { name: 'Generate' }));
    await screen.findByRole('alert');
    expect(body).toEqual({ ingredients: ['rice', 'egg'], mealType: null, onlyWhatIHave: false });
  });

  it('photosClearedAfterScan', async () => {
    signedIn();
    scanReturns({ ingredients: ['egg'] });
    const { user, input } = await openCook();
    await pick(user, input, 'a', 'b');
    await user.click(scanButton());
    await screen.findByText(/^Found 1 ingredient\./);

    expect(screen.queryByRole('button', { name: 'Remove photo 1' })).not.toBeInTheDocument();
    expect(screen.queryByRole('img', { name: /^Photo \d$/ })).not.toBeInTheDocument();
    expect(revokeUrl).toHaveBeenCalledWith('blob:photo-1');
    expect(revokeUrl).toHaveBeenCalledWith('blob:photo-2');
    expect(scanButton()).toBeDisabled();
  });

  it('shrinkFailure_showsUnreadableMessage', async () => {
    signedIn();
    vi.mocked(shrinkPhoto).mockRejectedValueOnce(new PhotoUnreadableError());
    const { user, input } = await openCook();
    await pick(user, input, 'a');
    await user.click(scanButton());

    expect(await screen.findByRole('alert')).toHaveTextContent(
      "This photo type can't be opened here. Try a JPEG or take a new photo.",
    );
    expect(screen.getByRole('button', { name: 'Remove photo 1' })).toBeInTheDocument();
  });

  it('has no emoji', async () => {
    signedIn();
    scanReturns({ ingredients: ['egg'], leftOut: [{ ingredient: 'kale', reason: 'dislike' }] });
    const { user, input } = await openCook();
    await pick(user, input, 'a');
    await user.click(scanButton());
    await screen.findByText(/^Found 1 ingredient\./);
    expectNoEmoji();
  });
});
