import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const upload = vi.fn();
const remove = vi.fn();
const from = vi.fn((_bucket: string) => ({ upload, remove }));

vi.mock('@/auth/supabaseClient', () => ({
  supabase: { storage: { from: (b: string) => from(b) } },
}));

import {
  ACCEPTED_TYPES, MAX_PHOTOS, MAX_PHOTO_BYTES, PHOTO_BUCKET,
  deletePhotos, pathFromPublicUrl, publicPhotoUrl, uploadPhoto,
} from './photos';

beforeEach(() => { upload.mockReset(); remove.mockReset(); from.mockClear(); });

describe('constants', () => {
  it('match the bucket rules', () => {
    expect(PHOTO_BUCKET).toBe('recipe-photos');
    expect(MAX_PHOTOS).toBe(6);
    expect(MAX_PHOTO_BYTES).toBe(5_242_880);
    expect(ACCEPTED_TYPES).toEqual(['image/jpeg', 'image/png', 'image/webp']);
  });
});

describe('public URLs', () => {
  it('round-trips a storage path', () => {
    expect(pathFromPublicUrl(publicPhotoUrl('u/x.webp'))).toBe('u/x.webp');
  });
  it('builds the public storage URL', () => {
    expect(publicPhotoUrl('u/x.webp')).toMatch(/\/storage\/v1\/object\/public\/recipe-photos\/u\/x\.webp$/);
  });
  it('returns null for URLs outside the bucket', () => {
    expect(pathFromPublicUrl('https://img.spoonacular.com/a.jpg')).toBeNull();
    expect(pathFromPublicUrl('https://img.spoonacular.com/x.jpg')).toBeNull();
  });
  it('parses the path from any host', () => {
    expect(pathFromPublicUrl('https://other.host/storage/v1/object/public/recipe-photos/u/a.webp')).toBe('u/a.webp');
  });
});

describe('public URLs with a trailing-slash base', () => {
  afterEach(() => { vi.unstubAllEnvs(); vi.resetModules(); });
  it('still round-trips', async () => {
    vi.stubEnv('VITE_SUPABASE_URL', 'https://proj.supabase.co//');
    vi.resetModules();
    const mod = await import('./photos');
    const url = mod.publicPhotoUrl('u/x.webp');
    expect(url).toBe('https://proj.supabase.co/storage/v1/object/public/recipe-photos/u/x.webp');
    expect(mod.pathFromPublicUrl(url)).toBe('u/x.webp');
  });
});

describe('uploadPhoto', () => {
  it('uploads to {userId}/{uuid}.{ext} and returns the path', async () => {
    upload.mockResolvedValue({ data: {}, error: null });
    const blob = new Blob(['x'], { type: 'image/webp' });
    const path = await uploadPhoto('u', blob, 'webp');
    expect(path).toMatch(/^u\/[0-9a-f-]{36}\.webp$/);
    expect(from).toHaveBeenCalledWith('recipe-photos');
    expect(upload).toHaveBeenCalledWith(path, blob, { contentType: 'image/webp', upsert: false });
  });
  it('throws an Error with the storage message on failure', async () => {
    upload.mockResolvedValue({ data: null, error: { message: 'Payload too large' } });
    await expect(uploadPhoto('u', new Blob(['x'], { type: 'image/webp' }), 'webp'))
      .rejects.toThrow('Payload too large');
  });
});

describe('deletePhotos', () => {
  it('does nothing for an empty list', async () => {
    await deletePhotos([]);
    expect(remove).not.toHaveBeenCalled();
  });
  it('removes the given paths', async () => {
    remove.mockResolvedValue({ data: [{ name: 'u/a.webp' }], error: null });
    await deletePhotos(['u/a.webp']);
    expect(remove).toHaveBeenCalledWith(['u/a.webp']);
  });
  it('warns when storage removed fewer files than requested', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    remove.mockResolvedValue({ data: [], error: null });
    await expect(deletePhotos(['u/a.webp', 'u/b.webp'])).resolves.toBeUndefined();
    expect(warn).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });
  it('warns and never throws on failure', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    remove.mockResolvedValue({ data: null, error: { message: 'nope' } });
    await expect(deletePhotos(['u/a.webp'])).resolves.toBeUndefined();
    remove.mockRejectedValue(new Error('network'));
    await expect(deletePhotos(['u/a.webp'])).resolves.toBeUndefined();
    expect(warn).toHaveBeenCalledTimes(2);
    warn.mockRestore();
  });
});
