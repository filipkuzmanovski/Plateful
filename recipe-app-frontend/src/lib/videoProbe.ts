// The only browser-media code in the app. jsdom cannot decode media, so tests replace this module.

const PROBE_TIMEOUT_MS = 15_000;
const MAX_POSTER_SIDE = 1280;
const POSTER_QUALITY = 0.85;

type ProbeResult = { playable: true; durationSeconds: number } | { playable: false };

function detachedVideo(src: string, preload: 'metadata' | 'auto'): HTMLVideoElement {
  const video = document.createElement('video');
  video.muted = true;
  video.playsInline = true;
  video.preload = preload;
  video.src = src;
  return video;
}

const hasDuration = (video: HTMLVideoElement): boolean => Number.isFinite(video.duration) && video.duration > 0;
// An audio-only file reports 0x0: it would "play" but has nothing to show as a poster.
const hasPicture = (video: HTMLVideoElement): boolean => video.videoWidth > 0 && video.videoHeight > 0;

function release(video: HTMLVideoElement, url: string): void {
  video.onerror = null;
  video.onloadedmetadata = null;
  video.onloadeddata = null;
  video.onseeked = null;
  video.removeAttribute('src');
  video.load();
  URL.revokeObjectURL(url);
}

/** Loads only the metadata. Unplayable on a decode error, no picture, a zero/infinite/NaN duration, or after 15 s. */
export function probeVideo(file: File): Promise<ProbeResult> {
  const url = URL.createObjectURL(file);
  const video = detachedVideo(url, 'metadata');
  return new Promise<ProbeResult>((resolve) => {
    const timer = setTimeout(() => finish({ playable: false }), PROBE_TIMEOUT_MS);
    function finish(result: ProbeResult) {
      clearTimeout(timer);
      release(video, url);
      resolve(result);
    }
    video.onerror = () => finish({ playable: false });
    video.onloadedmetadata = () => {
      try {
        finish(hasDuration(video) && hasPicture(video)
          ? { playable: true, durationSeconds: video.duration }
          : { playable: false });
      } catch {
        finish({ playable: false });
      }
    };
  });
}

/** A JPEG frame (longest side at most 1280 px) taken at min(atSeconds, duration / 2). */
export function capturePoster(file: File, atSeconds: number): Promise<Blob> {
  const url = URL.createObjectURL(file);
  // 'auto' and waiting for loadeddata: Safari/iOS otherwise seeks into a frame it has not decoded and draws blank.
  const video = detachedVideo(url, 'auto');
  return new Promise<Blob>((resolve, reject) => {
    const timer = setTimeout(() => fail(new Error('Timed out capturing the poster')), PROBE_TIMEOUT_MS);
    function settle() {
      clearTimeout(timer);
      release(video, url);
    }
    function fail(e: unknown) {
      settle();
      reject(e instanceof Error ? e : new Error('Could not capture the poster'));
    }
    video.onerror = () => fail(new Error('Could not decode the video'));
    video.onloadeddata = () => {
      try {
        if (!hasDuration(video) || !hasPicture(video)) throw new Error('The video has no usable picture');
        video.currentTime = Math.min(atSeconds, video.duration / 2);
      } catch (e) { fail(e); }
    };
    video.onseeked = () => {
      try {
        const scale = Math.min(1, MAX_POSTER_SIDE / Math.max(video.videoWidth, video.videoHeight));
        const canvas = document.createElement('canvas');
        canvas.width = Math.max(1, Math.round(video.videoWidth * scale));
        canvas.height = Math.max(1, Math.round(video.videoHeight * scale));
        const ctx = canvas.getContext('2d');
        if (!ctx) throw new Error('Canvas is not available');
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        canvas.toBlob((blob) => {
          if (!blob) return fail(new Error('Could not encode the poster'));
          settle();
          resolve(blob);
        }, 'image/jpeg', POSTER_QUALITY);
      } catch (e) { fail(e); }
    };
  });
}
