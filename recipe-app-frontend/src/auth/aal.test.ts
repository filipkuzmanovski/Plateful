import type { Session } from '@supabase/supabase-js';
import { aalOf } from './aal';

const b64url = (o: unknown) =>
  btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const jwt = (payload: unknown) => `${b64url({ alg: 'HS256' })}.${b64url(payload)}.sig`;
const sessionWith = (access_token: string) => ({ access_token }) as unknown as Session;

test('reads aal2 from the access token payload', () => {
  expect(aalOf(sessionWith(jwt({ aal: 'aal2' })))).toBe('aal2');
});

test('reads aal1 from the access token payload', () => {
  expect(aalOf(sessionWith(jwt({ aal: 'aal1' })))).toBe('aal1');
});

test('a non-JWT token gives null', () => {
  expect(aalOf(sessionWith('test-token'))).toBeNull();
});

test('a null session gives null', () => {
  expect(aalOf(null)).toBeNull();
});

test('a missing or unknown aal claim gives null', () => {
  expect(aalOf(sessionWith(jwt({ sub: 'u-1' })))).toBeNull();
  expect(aalOf(sessionWith(jwt({ aal: 'aal9' })))).toBeNull();
});

test('a malformed base64 token gives null and does not throw', () => {
  expect(() => aalOf(sessionWith('a.%%%not-base64%%%.c'))).not.toThrow();
  expect(aalOf(sessionWith('a.%%%not-base64%%%.c'))).toBeNull();
  expect(aalOf(sessionWith('a.e30.c'))).toBeNull(); // valid base64, no claim
  expect(aalOf(sessionWith('a.bm90LWpzb24.c'))).toBeNull(); // valid base64, not JSON
});
