import { supabase } from '@/auth/supabaseClient';

export const VIDEO_BUCKET = 'recipe-videos';
export const MAX_VIDEO_BYTES = 52_428_800;
export const MAX_VIDEO_SECONDS = 60;
export const ACCEPTED_VIDEO_TYPES = ['video/mp4', 'video/webm', 'video/quicktime'];

const EXTENSIONS: Record<string, 'mp4' | 'webm' | 'mov'> = {
  'video/mp4': 'mp4',
  'video/webm': 'webm',
  'video/quicktime': 'mov',
};

// Same fallback as supabaseClient.ts, so build and parse always round-trip.
const SUPABASE_URL: string = (import.meta.env.VITE_SUPABASE_URL ?? 'http://localhost:54321').replace(/\/+$/, '');
const PUBLIC_MARKER = `/storage/v1/object/public/${VIDEO_BUCKET}/`;

export const publicVideoUrl = (path: string): string => `${SUPABASE_URL}${PUBLIC_MARKER}${path}`;

// Host-independent on purpose: the backend builds URLs from its own base-url property, which may
// differ from VITE_SUPABASE_URL (custom domain, staging). A mismatch must not hide a recipe's video.
export function pathFromPublicVideoUrl(url: string): string | null {
  const i = url.indexOf(PUBLIC_MARKER);
  return i === -1 ? null : url.slice(i + PUBLIC_MARKER.length);
}

async function put(path: string, body: Blob, contentType: string): Promise<string> {
  const { error } = await supabase.storage.from(VIDEO_BUCKET).upload(path, body, { contentType, upsert: false });
  if (error) throw new Error(error.message);
  return path;
}

export async function uploadVideo(userId: string, file: File): Promise<string> {
  const ext = EXTENSIONS[file.type];
  if (!ext) throw new Error(`Unsupported video type: ${file.type}`);
  return put(`${userId}/${crypto.randomUUID()}.${ext}`, file, file.type);
}

export function uploadPoster(userId: string, blob: Blob): Promise<string> {
  return put(`${userId}/${crypto.randomUUID()}.jpg`, blob, 'image/jpeg');
}

// Best-effort cleanup: a failed delete must never break the user's flow.
export async function deleteVideoFiles(paths: string[]): Promise<void> {
  if (paths.length === 0) return;
  try {
    const { data, error } = await supabase.storage.from(VIDEO_BUCKET).remove(paths);
    if (error) console.warn('Could not delete video files', error.message);
    // Storage answers { data: [] } with no error when a policy hides the rows (e.g. a missing SELECT policy).
    else if (!data || data.length !== paths.length) {
      console.warn(`Storage removed ${data?.length ?? 0} of ${paths.length} video files`);
    }
  } catch (e) {
    console.warn('Could not delete video files', e);
  }
}
