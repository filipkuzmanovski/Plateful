import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { expectNoEmoji } from '@/test/noEmoji';
import { recipeFixture } from '@/test/fixtures';
import { RecipeCard } from '@/components/RecipeCard';
import { PhotoCollage } from './PhotoCollage';

// The bookmark needs auth and query providers that have nothing to do with the badge.
vi.mock('@/features/saved/BookmarkButton', () => ({ BookmarkButton: () => null }));

const video = { url: 'https://cdn.example.com/clip.mp4', posterUrl: 'https://cdn.example.com/poster.jpg', durationSeconds: 42 };
const urls = (n: number) => Array.from({ length: n }, (_, i) => `https://cdn.example.com/p${i + 1}.webp`);

/** A controllable IntersectionObserver: tests decide when a tile becomes visible. */
class FakeIntersectionObserver {
  static instances: FakeIntersectionObserver[] = [];
  readonly targets = new Set<Element>();
  readonly callback: IntersectionObserverCallback;
  readonly options?: IntersectionObserverInit;
  constructor(callback: IntersectionObserverCallback, options?: IntersectionObserverInit) {
    this.callback = callback;
    this.options = options;
    FakeIntersectionObserver.instances.push(this);
  }
  observe(el: Element) { this.targets.add(el); }
  unobserve(el: Element) { this.targets.delete(el); }
  disconnect() { this.targets.clear(); }
  takeRecords() { return []; }
  /** Report `ratio` of every watched element as visible. */
  show(ratio: number) {
    this.showSequence([ratio]);
  }
  /** Deliver several readings in one callback, oldest first, as a browser does when it batches them. */
  showSequence(ratios: number[]) {
    const entries = ratios.flatMap((ratio) =>
      [...this.targets].map(
        (target) => ({ target, isIntersecting: ratio > 0, intersectionRatio: ratio }) as IntersectionObserverEntry,
      ),
    );
    act(() => this.callback(entries, this as unknown as IntersectionObserver));
  }
}

/** The observer that watches `tile`'s video; there are two tiles (phone and wide layouts) in the DOM. */
function observerOf(tile: HTMLElement) {
  const el = tile.querySelector('video');
  const found = FakeIntersectionObserver.instances.find((o) => el && o.targets.has(el));
  if (!found) throw new Error('no observer is watching the tile');
  return found;
}

let play: ReturnType<typeof vi.spyOn>;
let pause: ReturnType<typeof vi.spyOn>;

function setReducedMotion(reduce: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: reduce && query.includes('prefers-reduced-motion'),
    media: query,
    addEventListener() {},
    removeEventListener() {},
    addListener() {},
    removeListener() {},
    dispatchEvent: () => false,
    onchange: null,
  }));
}

beforeEach(() => {
  FakeIntersectionObserver.instances = [];
  vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver);
  setReducedMotion(false);
  play = vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(() => Promise.resolve());
  pause = vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {});
});

afterEach(() => {
  cleanup(); // unmount while pause() is still stubbed: the viewer pauses its video on unmount
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function renderCollage(photoCount: number, withVideo = true) {
  render(<PhotoCollage photos={urls(photoCount)} video={withVideo ? video : null} title="Pasta" />);
  return {
    wide: within(screen.getByTestId('collage-desktop')),
    phone: within(screen.getByTestId('collage-phone')),
    wideEl: screen.getByTestId('collage-desktop'),
  };
}

test('the video is the second tile, after the cover photo', () => {
  const { wide } = renderCollage(3);
  const tiles = wide.getAllByRole('button');
  expect(tiles.map((t) => t.getAttribute('aria-label'))).toEqual([
    'Open photo 1 of 3',
    'Play video, 42 seconds',
    'Open photo 2 of 3',
  ]);
});

test('a recipe with only a video shows it as the cover', () => {
  const { wide, phone } = renderCollage(0);
  expect(wide.getAllByRole('button')).toHaveLength(1);
  expect(wide.getByRole('button', { name: 'Play video, 42 seconds' })).toBeInTheDocument();
  expect(phone.getAllByRole('button')).toHaveLength(1);
  expect(screen.queryByRole('img')).not.toBeInTheDocument();
});

test('the video counts as a tile for the +N overlay', () => {
  const { wide } = renderCollage(4); // 4 photos + video = 5 tiles, 3 shown, 2 hidden
  const third = wide.getByRole('button', { name: 'Open photo 2 of 4' });
  expect(within(third).getByText('+2')).toBeInTheDocument();
});

test('the tile is labelled with the length and shows it as m:ss', () => {
  const { wide } = renderCollage(1);
  const tile = wide.getByRole('button', { name: 'Play video, 42 seconds' });
  expect(within(tile).getByText('0:42')).toBeInTheDocument();
  expectNoEmoji();
});

test('the tile video is muted, looping, inline, not preloaded, and has the poster', () => {
  const { wide } = renderCollage(1);
  const el = wide.getByRole('button', { name: 'Play video, 42 seconds' }).querySelector('video')!;
  expect(el.muted).toBe(true);
  expect(el).toHaveAttribute('loop');
  expect(el).toHaveAttribute('playsinline');
  expect(el).toHaveAttribute('preload', 'none');
  expect(el).toHaveAttribute('poster', video.posterUrl);
  expect(el).toHaveAttribute('src', video.url);
  expect(el).not.toHaveAttribute('controls');
});

test('the tile watches for half of it being visible', () => {
  const { wide } = renderCollage(1);
  const tile = wide.getByRole('button', { name: 'Play video, 42 seconds' });
  expect(observerOf(tile).options?.threshold).toBe(0.5);
  expect(play).not.toHaveBeenCalled(); // nothing starts before the tile is on screen
});

test('it plays while at least half visible and pauses when it is not', () => {
  const { wide } = renderCollage(1);
  const tile = wide.getByRole('button', { name: 'Play video, 42 seconds' });
  const observer = observerOf(tile);

  observer.show(0.6);
  expect(play).toHaveBeenCalledTimes(1);
  expect(pause).not.toHaveBeenCalled();

  observer.show(0.2);
  expect(pause).toHaveBeenCalledTimes(1);

  observer.show(1);
  expect(play).toHaveBeenCalledTimes(2);
});

test('it never autoplays with reduced motion, and the poster stays', () => {
  setReducedMotion(true);
  const { wide } = renderCollage(1);
  const tile = wide.getByRole('button', { name: 'Play video, 42 seconds' });
  observerOf(tile).show(1);
  expect(play).not.toHaveBeenCalled();
  expect(tile.querySelector('video')).toHaveAttribute('poster', video.posterUrl);
});

test('it does not autoplay where IntersectionObserver is missing', () => {
  vi.stubGlobal('IntersectionObserver', undefined);
  const { wide } = renderCollage(1);
  expect(wide.getByRole('button', { name: 'Play video, 42 seconds' })).toBeInTheDocument();
  expect(play).not.toHaveBeenCalled();
});

// An unswallowed rejection would also fail the run: vitest reports unhandled rejections as errors.
test('a rejected play() is swallowed and the poster stays', async () => {
  const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
  play.mockImplementation(() => Promise.reject(new DOMException('blocked', 'NotAllowedError')));

  const { wide } = renderCollage(1);
  const tile = wide.getByRole('button', { name: 'Play video, 42 seconds' });
  observerOf(tile).show(1);
  await act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });

  expect(play).toHaveBeenCalled();
  expect(consoleError).not.toHaveBeenCalled();
  expect(tile.querySelector('video')).toHaveAttribute('poster', video.posterUrl);
});

test('the arrows go photo 1, the video, then photo 2', async () => {
  const user = userEvent.setup();
  const { wide } = renderCollage(2);
  await user.click(wide.getByRole('button', { name: 'Open photo 1 of 2' }));
  const dialog = await screen.findByRole('dialog');
  expect(within(dialog).getByAltText('Pasta, photo 1 of 2')).toBeInTheDocument();
  expect(within(dialog).getByText('1 / 3')).toBeInTheDocument();

  await user.click(within(dialog).getByRole('button', { name: 'Next' }));
  expect(within(dialog).getByText('2 / 3')).toBeInTheDocument();
  expect(dialog.querySelector('video')).toBeInTheDocument();
  expect(within(dialog).queryByRole('img')).not.toBeInTheDocument();

  await user.click(within(dialog).getByRole('button', { name: 'Next' }));
  expect(within(dialog).getByAltText('Pasta, photo 2 of 2')).toBeInTheDocument();
  expect(dialog.querySelector('video')).not.toBeInTheDocument();
});

async function openViewerOnVideo() {
  const user = userEvent.setup();
  const { wide } = renderCollage(2);
  await user.click(wide.getByRole('button', { name: 'Play video, 42 seconds' }));
  const dialog = await screen.findByRole('dialog');
  return { user, dialog, el: dialog.querySelector('video')! };
}

test('opening the video tile opens the viewer on the video, with controls and sound', async () => {
  const { dialog, el } = await openViewerOnVideo();
  expect(within(dialog).getByText('2 / 3')).toBeInTheDocument();
  expect(el).toHaveAttribute('src', video.url);
  expect(el).toHaveAttribute('controls');
  expect(el.autoplay).toBe(true);
  expect(el).toHaveAttribute('playsinline');
  expect(el.muted).toBe(false);
  expect(el.loop).toBe(false);
  expectNoEmoji();
});

test('closing the viewer pauses the video', async () => {
  const { user, dialog } = await openViewerOnVideo();
  pause.mockClear();
  await user.click(within(dialog).getByRole('button', { name: 'Close' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(pause).toHaveBeenCalled();
});

test('navigating away from the video pauses it', async () => {
  const { user, dialog } = await openViewerOnVideo();
  pause.mockClear();
  await user.click(within(dialog).getByRole('button', { name: 'Next' }));
  expect(dialog.querySelector('video')).not.toBeInTheDocument();
  expect(pause).toHaveBeenCalled();
});

test('the viewer says so when the clip fails to load', async () => {
  const { dialog, el } = await openViewerOnVideo();
  expect(within(dialog).queryByText("This video couldn't be played.")).not.toBeInTheDocument();
  fireEvent.error(el);
  expect(within(dialog).getByText("This video couldn't be played.")).toBeInTheDocument();
  expect(dialog.querySelector('video')).not.toBeInTheDocument();
  expectNoEmoji();
});

test('the arrow keys on the playing video are left to the player for seeking', async () => {
  const { dialog, el } = await openViewerOnVideo();
  fireEvent.keyDown(el, { key: 'ArrowRight' });
  fireEvent.keyDown(el, { key: 'ArrowLeft' });
  expect(within(dialog).getByText('2 / 3')).toBeInTheDocument();
  expect(dialog.querySelector('video')).toBe(el);
});

test('the viewer video is named', async () => {
  const { el } = await openViewerOnVideo();
  expect(el).toHaveAttribute('aria-label', 'Pasta, video');
});

test('a one second clip is labelled in the singular', () => {
  render(<PhotoCollage photos={[]} video={{ ...video, durationSeconds: 1 }} title="Pasta" />);
  expect(within(screen.getByTestId('collage-desktop')).getByRole('button', { name: 'Play video, 1 second' })).toBeInTheDocument();
});

test('with several readings in one callback, the latest one decides', () => {
  const { wide } = renderCollage(1);
  const observer = observerOf(wide.getByRole('button', { name: 'Play video, 42 seconds' }));
  observer.showSequence([0, 1]);
  expect(play).toHaveBeenCalledTimes(1);
  expect(pause).not.toHaveBeenCalled();

  play.mockClear();
  observer.showSequence([1, 0]);
  expect(play).not.toHaveBeenCalled();
  expect(pause).toHaveBeenCalledTimes(1);
});

describe('the tile while the viewer is open', () => {
  const tileVideo = (wide: ReturnType<typeof renderCollage>['wide']) =>
    wide.getByRole('button', { name: 'Play video, 42 seconds' }).querySelector('video')!;
  const playsOf = (el: Element) => play.mock.contexts.filter((c: unknown) => c === el).length;
  const pausesOf = (el: Element) => pause.mock.contexts.filter((c: unknown) => c === el).length;

  test('opening the viewer pauses the tile, and it is not played again while open', async () => {
    const user = userEvent.setup();
    const { wide } = renderCollage(1);
    const tile = wide.getByRole('button', { name: 'Play video, 42 seconds' });
    const el = tileVideo(wide);
    const observer = observerOf(tile);
    observer.show(1);
    expect(playsOf(el)).toBe(1);

    await user.click(tile);
    await screen.findByRole('dialog');
    expect(pausesOf(el)).toBe(1);

    observer.show(0.1);
    observer.show(1); // scrolled back into view behind the overlay
    expect(playsOf(el)).toBe(1);
  });

  test('closing it while the tile is visible resumes playback', async () => {
    const user = userEvent.setup();
    const { wide } = renderCollage(1);
    const tile = wide.getByRole('button', { name: 'Play video, 42 seconds' });
    const el = tileVideo(wide);
    observerOf(tile).show(1);
    await user.click(tile);
    await user.click(await screen.findByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(playsOf(el)).toBe(2);
  });

  test('closing it while the tile is out of view does not play', async () => {
    const user = userEvent.setup();
    const { wide } = renderCollage(1);
    const tile = wide.getByRole('button', { name: 'Play video, 42 seconds' });
    const el = tileVideo(wide);
    const observer = observerOf(tile);
    observer.show(1);
    await user.click(tile);
    await screen.findByRole('dialog');
    observer.show(0); // scrolled away while the viewer was open
    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(playsOf(el)).toBe(1);
  });
});

describe('RecipeCard', () => {
  const renderCard = (overrides = {}) =>
    render(
      <MemoryRouter>
        <RecipeCard recipe={recipeFixture(overrides)} />
      </MemoryRouter>,
    );

  test('shows a Video badge when the recipe has a video', () => {
    renderCard({ video });
    const link = screen.getByRole('link');
    expect(within(link).getByText('Video')).toBeInTheDocument();
    expectNoEmoji();
  });

  test('has no badge without a video', () => {
    renderCard({ video: null });
    expect(screen.queryByText('Video')).not.toBeInTheDocument();
  });

  test('the badge sits beside the review status badge', () => {
    renderCard({
      video,
      review: { status: 'pending', rejectionReason: null, everApproved: false, revision: 1, submittedAt: '2026-10-18T10:00:00Z' },
    });
    expect(screen.getByText('Video')).toBeInTheDocument();
    expect(screen.getByText(/pending/i)).toBeInTheDocument();
  });
});
