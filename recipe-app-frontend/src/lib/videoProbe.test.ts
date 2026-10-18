import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { capturePoster, probeVideo } from './videoProbe';

// jsdom cannot decode media, so a plain object stands in for the <video> element and the test drives its handlers.
interface FakeVideo {
  muted: boolean; playsInline: boolean; preload: string; src: string;
  duration: number; videoWidth: number; videoHeight: number;
  currentTime: number;
  onerror: (() => void) | null; onloadedmetadata: (() => void) | null;
  onloadeddata: (() => void) | null; onseeked: (() => void) | null;
  removeAttribute: ReturnType<typeof vi.fn>; load: ReturnType<typeof vi.fn>;
}

let video: FakeVideo;
const revoke = vi.fn();

beforeEach(() => {
  revoke.mockReset();
  video = {
    muted: false, playsInline: false, preload: '', src: '', duration: 10, videoWidth: 640, videoHeight: 360,
    currentTime: 0, onerror: null, onloadedmetadata: null, onloadeddata: null, onseeked: null,
    removeAttribute: vi.fn(), load: vi.fn(),
  };
  const real = document.createElement.bind(document);
  vi.spyOn(document, 'createElement').mockImplementation(((tag: string) =>
    tag === 'video' ? video : real(tag)) as typeof document.createElement);
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fake');
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(revoke);
});
afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers(); });

const file = new File(['x'], 'c.mp4', { type: 'video/mp4' });

function expectDetached() {
  expect(video.onerror).toBeNull();
  expect(video.onloadedmetadata).toBeNull();
  expect(video.onloadeddata).toBeNull();
  expect(video.onseeked).toBeNull();
  expect(revoke).toHaveBeenCalledWith('blob:fake');
}

describe('probeVideo', () => {
  it('reports the duration of a playable video and cleans up', async () => {
    const p = probeVideo(file);
    video.duration = 12.4;
    video.onloadedmetadata!();
    await expect(p).resolves.toEqual({ playable: true, durationSeconds: 12.4 });
    expectDetached();
  });
  it.each([[0, 360], [640, 0]])('treats %ix%i (audio-only) as unplayable', async (w, h) => {
    const p = probeVideo(file);
    video.videoWidth = w; video.videoHeight = h;
    video.onloadedmetadata!();
    await expect(p).resolves.toEqual({ playable: false });
    expectDetached();
  });
  it.each([0, NaN, Infinity])('treats a duration of %s as unplayable', async (d) => {
    const p = probeVideo(file);
    video.duration = d;
    video.onloadedmetadata!();
    await expect(p).resolves.toEqual({ playable: false });
  });
  it('is unplayable on a decode error', async () => {
    const p = probeVideo(file);
    video.onerror!();
    await expect(p).resolves.toEqual({ playable: false });
    expectDetached();
  });
  it('gives up after 15 seconds', async () => {
    vi.useFakeTimers();
    const p = probeVideo(file);
    await vi.advanceTimersByTimeAsync(15_000);
    await expect(p).resolves.toEqual({ playable: false });
    expectDetached();
  });
});

describe('capturePoster', () => {
  it('waits for loadeddata, then seeks to min(at, duration / 2)', async () => {
    const p = capturePoster(file, 1);
    expect(video.preload).toBe('auto');
    expect(video.onloadedmetadata).toBeNull();
    video.duration = 1.2;
    video.onloadeddata!();
    expect(video.currentTime).toBe(0.6);
    video.onerror!();
    await expect(p).rejects.toThrow();
  });
  it('rejects at once for a file with no picture', async () => {
    const p = capturePoster(file, 1);
    video.videoWidth = 0; video.videoHeight = 0;
    video.onloadeddata!();
    await expect(p).rejects.toThrow('no usable picture');
    expectDetached();
  });
  it('settles at once when a handler throws instead of waiting for the timeout', async () => {
    vi.useFakeTimers();
    const p = capturePoster(file, 1);
    Object.defineProperty(video, 'currentTime', { set() { throw new Error('bad seek'); }, configurable: true });
    video.onloadeddata!();
    await expect(p).rejects.toThrow('bad seek');
    expectDetached();
    expect(vi.getTimerCount()).toBe(0);
  });
  it('rejects when the canvas cannot draw', async () => {
    const p = capturePoster(file, 1);
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
    video.onloadeddata!();
    video.onseeked!();
    await expect(p).rejects.toThrow('Canvas is not available');
    expectDetached();
  });
  it('rejects after 15 seconds and cleans up', async () => {
    vi.useFakeTimers();
    const p = capturePoster(file, 1);
    const assertion = expect(p).rejects.toThrow('Timed out');
    await vi.advanceTimersByTimeAsync(15_000);
    await assertion;
    expectDetached();
  });
});
