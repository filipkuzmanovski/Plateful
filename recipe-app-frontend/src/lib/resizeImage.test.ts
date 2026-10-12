import { describe, expect, it, vi } from 'vitest';
import { prepareImage, targetSize } from './resizeImage';

const file = new File(['x'], 'p.png', { type: 'image/png' });
const fakeSrc = { width: 4000, height: 3000 } as unknown as ImageBitmap;

describe('targetSize', () => {
  it('scales the longest side down to 2000', () => {
    expect(targetSize(4000, 3000)).toEqual({ width: 2000, height: 1500 });
    expect(targetSize(3000, 4000)).toEqual({ width: 1500, height: 2000 });
  });
  it('never upscales', () => {
    expect(targetSize(800, 600)).toEqual({ width: 800, height: 600 });
  });
  it('rounds the results', () => {
    expect(targetSize(3001, 1999)).toEqual({ width: 2000, height: 1332 });
  });
});

describe('prepareImage', () => {
  it('encodes WebP at 0.85 on the shrunken size', async () => {
    const encode = vi.fn(async () => new Blob(['w'], { type: 'image/webp' }));
    const out = await prepareImage(file, { decode: async () => fakeSrc, encode });
    expect(out.ext).toBe('webp');
    expect(encode).toHaveBeenCalledWith(fakeSrc, 2000, 1500, 'image/webp', 0.85);
  });

  it('falls back to JPEG when WebP encoding returns null', async () => {
    const encode = vi.fn(async (_s: unknown, _w: number, _h: number, type: string) =>
      type === 'image/webp' ? null : new Blob(['j'], { type: 'image/jpeg' }));
    const out = await prepareImage(file, { decode: async () => fakeSrc, encode });
    expect(out.ext).toBe('jpg');
    expect(encode).toHaveBeenLastCalledWith(fakeSrc, 2000, 1500, 'image/jpeg', 0.85);
  });

  it('falls back to JPEG when the browser silently returns a non-WebP blob', async () => {
    const encode = vi.fn(async (_s: unknown, _w: number, _h: number, type: string) =>
      new Blob(['x'], { type: type === 'image/webp' ? 'image/png' : 'image/jpeg' }));
    const out = await prepareImage(file, { decode: async () => fakeSrc, encode });
    expect(out.ext).toBe('jpg');
    expect(out.blob.type).toBe('image/jpeg');
  });

  it("throws \"Couldn't read this image\" when decoding fails", async () => {
    const decode = async () => { throw new Error('bad'); };
    await expect(prepareImage(file, { decode, encode: async () => null }))
      .rejects.toThrow("Couldn't read this image");
  });

  it('throws when the result is over 5 MB', async () => {
    const big = new Blob([new Uint8Array(5_242_881)], { type: 'image/webp' });
    await expect(prepareImage(file, { decode: async () => fakeSrc, encode: async () => big }))
      .rejects.toThrow('Photo is too large even after resizing');
  });
});
