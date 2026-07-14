import { supabase } from '@/auth/supabaseClient';

const BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

export class ApiError extends Error {
  status: number;
  detail: string;
  errors?: Record<string, string>;

  constructor(status: number, detail: string, errors?: Record<string, string>) {
    super(detail);
    this.status = status;
    this.detail = detail;
    this.errors = errors;
  }
}

async function request(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  if (init.body) headers.set('Content-Type', 'application/json');
  const { data } = await supabase.auth.getSession();
  const token = data.session?.access_token;
  if (token) headers.set('Authorization', `Bearer ${token}`);

  const res = await fetch(`${BASE}${path}`, { ...init, headers });
  if (res.ok) return res;

  if (res.status === 401) await supabase.auth.signOut(); // expired/invalid token — routing reacts to session loss
  const problem = await res.json().catch(() => null);
  throw new ApiError(res.status, problem?.detail ?? `Request failed (${res.status})`, problem?.errors);
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
