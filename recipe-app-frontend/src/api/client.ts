import { supabase } from '@/auth/supabaseClient';

const BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

export class ApiError extends Error {
  status: number;
  detail: string;
  errors?: Record<string, string>;
  code?: string;
  approvedTitle?: string;

  constructor(
    status: number,
    detail: string,
    errors?: Record<string, string>,
    code?: string,
    approvedTitle?: string,
  ) {
    super(detail);
    this.status = status;
    this.detail = detail;
    this.errors = errors;
    this.code = code;
    this.approvedTitle = approvedTitle;
  }
}

let mfaRequiredHandler: (() => void) | null = null;

/** Registers the callback run when the API answers 401 `mfa_required` (half signed-in session). */
export function setMfaRequiredHandler(fn: (() => void) | null): void {
  mfaRequiredHandler = fn;
}

async function request(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  if (init.body) headers.set('Content-Type', 'application/json');
  const { data } = await supabase.auth.getSession();
  const token = data.session?.access_token;
  if (token) headers.set('Authorization', `Bearer ${token}`);

  const res = await fetch(`${BASE}${path}`, { ...init, headers });
  if (res.ok) return res;

  const problem = await res.json().catch(() => null);
  if (res.status === 401) {
    if (problem?.code === 'mfa_required') {
      // Signed in but not yet at the second factor: keep the session, let the app route to verification.
      mfaRequiredHandler?.();
    } else {
      await supabase.auth.signOut(); // expired/invalid token — routing reacts to session loss
    }
  }
  throw new ApiError(res.status, problem?.detail ?? `Request failed (${res.status})`, problem?.errors, problem?.code, problem?.approvedTitle);
}

/** JSON-returning call. */
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await request(path, init);
  return res.json() as Promise<T>;
}

/** 204-returning call. */
export async function apiVoid(path: string, init: RequestInit = {}): Promise<void> {
  await request(path, init);
}
