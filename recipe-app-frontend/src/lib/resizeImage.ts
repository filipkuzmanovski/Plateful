import { MAX_PHOTO_BYTES } from '@/api/photos';

type Source = ImageBitmap | HTMLImageElement;

export interface ResizeDeps {
  decode?: (f: File) => Promise<Source>;
  encode?: (src: Source, w: number, h: number, type: string, quality: number) => Promise<Blob | null>;
}

export function targetSize(width: number, height: number, max = 2000): { width: number; height: number } {
  const longest = Math.max(width, height);
  if (longest <= max) return { width, height };
  const scale = max / longest;
  return { width: Math.round(width * scale), height: Math.round(height * scale) };
}

// Phone photos carry an EXIF orientation flag and re-encoding through a canvas drops it, so ask the
// browser to apply it while decoding. Older browsers reject the options object: retry without it.
const defaultDecode = async (f: File): Promise<Source> => {
  try {
    return await createImageBitmap(f, { imageOrientation: 'from-image' });
  } catch {
    return createImageBitmap(f);
  }
};

/**
 * Encodes through ONE canvas that is drawn once and reused for the WebP attempt and the JPEG
 * fallback (iOS Safari has a hard limit on total canvas memory). Call `release()` when done.
 */
function createCanvasEncoder() {
  let canvas: HTMLCanvasElement | null = null;
  let ctx: CanvasRenderingContext2D | null = null;

  const encode = (src: Source, w: number, h: number, type: string, quality: number): Promise<Blob | null> => {
    if (!canvas) {
      canvas = document.createElement('canvas');
      canvas.width = w;
      canvas.height = h;
      ctx = canvas.getContext('2d');
      if (ctx) ctx.drawImage(src, 0, 0, w, h);
    }
    if (!ctx) return Promise.resolve(null);
    if (type === 'image/jpeg') {
      // JPEG has no alpha: paint white behind the existing pixels so transparency doesn't turn black.
      ctx.globalCompositeOperation = 'destination-over';
      ctx.fillStyle = '#ffffff';
      ctx.fillRect(0, 0, w, h);
      ctx.globalCompositeOperation = 'source-over';
    }
    const target = canvas;
    return new Promise((resolve) => target.toBlob(resolve, type, quality));
  };

  const release = () => {
    if (canvas) {
      canvas.width = 0;
      canvas.height = 0;
    }
    canvas = null;
    ctx = null;
  };

  return { encode, release };
}

export async function prepareImage(
  file: File,
  deps: ResizeDeps = {},
): Promise<{ blob: Blob; ext: 'webp' | 'jpg' }> {
  const decode = deps.decode ?? defaultDecode;
  const canvasEncoder = deps.encode ? null : createCanvasEncoder();
  const encode = deps.encode ?? canvasEncoder!.encode;

  let src: Source;
  try {
    src = await decode(file);
  } catch {
    throw new Error("Couldn't read this image");
  }

  try {
    const { width, height } = targetSize(src.width, src.height);

    let blob: Blob | null;
    let ext: 'webp' | 'jpg' = 'webp';
    try {
      blob = await encode(src, width, height, 'image/webp', 0.85);
      // Some browsers silently answer a WebP request with PNG, so check the type too.
      if (!blob || blob.type !== 'image/webp') {
        blob = await encode(src, width, height, 'image/jpeg', 0.85);
        ext = 'jpg';
      }
    } catch {
      throw new Error("Couldn't read this image");
    }
    if (!blob) throw new Error("Couldn't read this image");
    if (blob.size > MAX_PHOTO_BYTES) throw new Error('Photo is too large even after resizing');
    return { blob, ext };
  } finally {
    if ('close' in src) src.close();
    canvasEncoder?.release();
  }
}
