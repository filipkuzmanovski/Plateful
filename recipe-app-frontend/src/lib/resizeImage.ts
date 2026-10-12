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

const defaultDecode = (f: File): Promise<Source> => createImageBitmap(f);

const defaultEncode = (src: Source, w: number, h: number, type: string, quality: number): Promise<Blob | null> => {
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d');
  if (!ctx) return Promise.resolve(null);
  ctx.drawImage(src, 0, 0, w, h);
  return new Promise((resolve) => canvas.toBlob(resolve, type, quality));
};

export async function prepareImage(
  file: File,
  deps: ResizeDeps = {},
): Promise<{ blob: Blob; ext: 'webp' | 'jpg' }> {
  const decode = deps.decode ?? defaultDecode;
  const encode = deps.encode ?? defaultEncode;

  let src: Source;
  try {
    src = await decode(file);
  } catch {
    throw new Error("Couldn't read this image");
  }

  const { width, height } = targetSize(src.width, src.height);

  let blob = await encode(src, width, height, 'image/webp', 0.85);
  let ext: 'webp' | 'jpg' = 'webp';
  // Some browsers silently answer a WebP request with PNG, so check the type too.
  if (!blob || blob.type !== 'image/webp') {
    blob = await encode(src, width, height, 'image/jpeg', 0.85);
    ext = 'jpg';
  }
  if (!blob) throw new Error("Couldn't read this image");
  if (blob.size > MAX_PHOTO_BYTES) throw new Error('Photo is too large even after resizing');
  return { blob, ext };
}
