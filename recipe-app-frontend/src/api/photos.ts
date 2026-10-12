import { supabase } from '@/auth/supabaseClient';

export const PHOTO_BUCKET = 'recipe-photos';
export const MAX_PHOTOS = 6;
export const MAX_PHOTO_BYTES = 5_242_880;
export const ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'image/webp'];

// Same fallback as supabaseClient.ts, so build and parse always round-trip.
const SUPABASE_URL: string = import.meta.env.VITE_SUPABASE_URL ?? 'http://localhost:54321';
const PUBLIC_PREFIX = `${SUPABASE_URL}/storage/v1/object/public/${PHOTO_BUCKET}/`;

export const publicPhotoUrl = (path: string): string => `${PUBLIC_PREFIX}${path}`;

export function pathFromPublicUrl(url: string): string | null {
  return url.startsWith(PUBLIC_PREFIX) ? url.slice(PUBLIC_PREFIX.length) : null;
}

export async function uploadPhoto(userId: string, blob: Blob, ext: 'jpg' | 'png' | 'webp'): Promise<string> {
  const path = `${userId}/${crypto.randomUUID()}.${ext}`;
  const { error } = await supabase.storage
    .from(PHOTO_BUCKET)
    .upload(path, blob, { contentType: blob.type, upsert: false });
  if (error) throw new Error(error.message);
  return path;
}

// Best-effort cleanup: a failed delete must never break the user's flow.
export async function deletePhotos(paths: string[]): Promise<void> {
  if (paths.length === 0) return;
  try {
    const { error } = await supabase.storage.from(PHOTO_BUCKET).remove(paths);
    if (error) console.warn('Could not delete photos', error.message);
  } catch (e) {
    console.warn('Could not delete photos', e);
  }
}
