# Two-factor authentication (TOTP): design

Date: 2026-10-13
Status: approved in conversation; this written spec is awaiting review.
Round 3 of 3, after seas and sorting, and the age check.

## 1. Goal

Users can protect their account with an authenticator app (Google Authenticator, Microsoft Authenticator, Authy, or a password manager with codes).

- Once it's on, signing in needs a 6-digit code after the password.
- The backend enforces this: a password alone can't change anything on an account that has 2FA.

## 2. Decisions from the conversation

| Question | Decision |
|---|---|
| Who uses 2FA? | Optional. Users turn it on from their profile. |
| Method | Authenticator-app codes (TOTP) through Supabase Auth MFA. It's free and built in. |
| Enforcement | Server-side. For accounts with 2FA, the backend accepts only a fully signed-in token (`aal2`) for account and write actions. Public browsing is unchanged. |
| Recovery | Up to 2 authenticator devices per account. If all are lost, the site owner removes the factors with one SQL line in the Supabase dashboard (section 7). |
| Approach | A Spring Security filter after JWT validation, one central rule (approach 1). |

## 3. Background: Supabase assurance levels

- After signing in with a password, the Supabase access token carries `aal: "aal1"`.
- After a TOTP code is verified, the session is refreshed and the new token carries `aal: "aal2"`.
- The client library reports `getAuthenticatorAssuranceLevel() → { currentLevel, nextLevel }`. When `currentLevel` is `aal1` and `nextLevel` is `aal2`, the user has a verified factor but hasn't entered a code yet. This spec calls that the "half signed-in" state.
- Supabase keeps factors in `auth.mfa_factors` (`user_id`, `status`, `friendly_name`, `secret`, ...).
  - The backend's existing database connection can read this table. This was verified read-only.
  - The backend reads only "does this user have a verified factor". It never selects `secret` or any other column.

## 4. Backend

### 4.1 Factor lookup

- New `security/MfaFactorLookup` (`@Component`).
- `boolean hasVerifiedFactor(UUID userId)` runs:
  `select exists(select 1 from auth.mfa_factors where user_id = ? and status = 'verified')`
  It uses `JdbcTemplate`, the only query against the `auth` schema.
- Results are cached per user for 60 seconds in a `ConcurrentHashMap<UUID, CachedResult>`.
  - The cached result holds the value and the time it expires. There's no new dependency.
  - Entries that are read after they expire get recomputed.
  - The cache is bounded to 10,000 entries: when full, it is cleared.
- **Staleness:** for up to 60 seconds after someone turns 2FA on, a half signed-in token may still pass. That's acceptable, because the person turning it on is the account owner.

### 4.2 Enforcement filter

- New `security/MfaEnforcementFilter extends OncePerRequestFilter`.
- It is added in `SecurityConfig` with `.addFilterAfter(mfaFilter, BearerTokenAuthenticationFilter.class)`.
- The filter applies only when all three of these hold:
  1. the request is authenticated with a `JwtAuthenticationToken`;
  2. the request needs an account, meaning the method is not `GET`, OR the path ends with `/me`.
     - That covers `/api/users/me`, `/api/recipes/*/ratings/me` and `/api/recipes/*/comments/reactions/me`.
     - Public GETs, such as browsing, a recipe, comments and profiles, are never blocked.
  3. the token's `aal` claim is not `"aal2"`.
- When those hold and `hasVerifiedFactor(sub)` is true, the filter answers itself and does not continue the chain:
  - status 401;
  - header `WWW-Authenticate: Bearer error="insufficient_user_authentication"` (RFC 9470 step-up);
  - body: a ProblemDetail JSON with `detail: "Two-factor verification required"` and `code: "mfa_required"`.
- Otherwise the request continues unchanged.
- **Fail-closed:** if the lookup throws, for example because the database is down, the filter answers `503` with `detail: "Could not verify two-factor status"`. It never lets the request through unchecked.

### 4.3 Unchanged

- No new endpoints and no schema changes.
- Enrolling, challenging, verifying and removing factors all happen directly between the browser and Supabase Auth, using the user's own session.
- Supabase itself requires `aal2` to remove a verified factor.

## 5. Frontend

### 5.1 API client

- `src/api/client.ts` currently calls `supabase.auth.signOut()` on every 401. It must NOT sign out when the problem body has `code === "mfa_required"`.
- `ApiError` gains `code?: string`, read from the problem body.
- A new event bus or callback, `onMfaRequired()`, lets `AuthProvider` react to it.

### 5.2 Auth state

- `AuthProvider`'s `Status` gains `'needs-mfa'`.
- **On session change:**
  1. First call `supabase.auth.mfa.getAuthenticatorAssuranceLevel()`.
  2. If `currentLevel === 'aal1' && nextLevel === 'aal2'`, set the status to `'needs-mfa'` and don't load the profile.
  3. Otherwise continue as today.
- An `mfa_required` ApiError at any point also sets `'needs-mfa'`.
- **Dedupe key:** the current dedupe key is the user id, which would ignore the session upgrade after a code is verified. The key becomes `userId + ":" + aal`, so verifying a code (`aal1 → aal2`) reloads the profile and moves the status to `'ready'`.
- **ProtectedRoute:** when the status is `'needs-mfa'` and the path is not `/verify-2fa`, it redirects to `/verify-2fa`, keeping `from`.
- **AppShell:** the user menu shows "Sign out" in the half signed-in state, so a user can always back out.

### 5.3 Sign-in code step: new route `/verify-2fa` (`features/auth/VerifyTwoFactorPage.tsx`)

- It reuses `AuthCard`, with the title "Two-factor verification" and the subtitle "Enter the 6-digit code from your authenticator app."
- It lists the verified TOTP factors with `mfa.listFactors()`.
  - With 2 factors, it shows a radio choice labelled "Which device?", using each factor's friendly name. The first is selected by default.
  - Each factor has its own secret, so the code must be checked against the chosen device.
- **The code field:** a text input with `inputMode="numeric"`, `autoComplete="one-time-code"`, `maxLength=6` and pattern `\d{6}`, labelled "Code".
- **"Verify":** calls `mfa.challenge({ factorId })` then `mfa.verify({ factorId, challengeId, code })`.
  - **On success:** the session becomes `aal2`, AuthProvider reloads, and the page navigates to `from` or `/`.
  - **On failure:** the FormError reads "That code didn't work. Check your app and try again." Supabase's own rate-limit message is shown as returned.
- **"Sign out"** is offered as a secondary link.
- **LoginPage:** after `signInWithPassword` succeeds, it checks the AAL. If the user is half signed-in, it navigates to `/verify-2fa` with `from`. Otherwise it behaves as today.

### 5.4 Settings: profile page section "Two-factor authentication" (`features/profile/TwoFactorSettings.tsx`)

- **Status line:** "Off", or "On: N device(s)".
- **Turn on and add a device** (offered while there are fewer than 2 verified factors):
  1. A "Device name" input, defaulting to "Authenticator 1" or "Authenticator 2". The name must be unique per user, because Supabase requires it.
  2. Unverified TOTP factors left over from an abandoned setup are removed first (`mfa.unenroll`), because Supabase keeps them otherwise.
  3. `mfa.enroll({ factorType: 'totp', friendlyName })` returns `totp.qr_code` (an SVG data URL), `totp.secret` and `id`.
  4. The page shows the QR code as `<img alt="QR code for your authenticator app">`, the secret in a monospace "Can't scan? Enter this key" line, and a "Code" input.
  5. "Confirm" calls challenge plus verify against the new factor.
     - Success shows "Two-factor authentication is on" and refreshes the list.
     - Failure shows the same wrong-code message as the sign-in step.
  6. "Cancel" unenrolls the unverified factor.
- **Device list:** each verified factor's friendly name and date added, with a "Remove" button labelled "Remove {name}".
  - Removing goes through the existing `ConfirmDialog`.
  - It needs an `aal2` session. If Supabase refuses because the session is not `aal2`, the page shows "Please sign in again with your code to make this change."
  - Removing the last device turns 2FA off. The dialog says so.
- **First factor and the session:** after verifying the first factor, the current session is already `aal2`, so the user stays signed in.

### 5.5 Style

Golden Hour tokens; secondary text `text-olive/70`; buttons for everything clickable; no emojis.

## 6. Testing

**Backend:**
- `MfaEnforcementFilterTest`, a WebMvc slice with `SecurityConfig` and `jwt()` claims:
  - A POST with `aal1` and no factor passes, giving the controller's normal status.
  - A POST with `aal1` and a factor gives 401, `code: "mfa_required"`, and the `WWW-Authenticate` header. The controller is not called.
  - A POST with `aal2` and a factor passes.
  - `GET /api/users/me` with `aal1` and a factor gives 401 `mfa_required`.
  - A public GET (`/api/recipes`) with `aal1` and a factor passes.
  - A lookup that throws gives 503.
  - An anonymous request is unaffected, so existing behaviour holds.
- `MfaFactorLookupTest`: the cache returns the cached value within the TTL and re-queries after it. Use an injectable clock.
- `MfaFactorLookupDbTest`, with the same annotations as the other DB tests and read-only: a random UUID gives false. The test also checks that the SQL text contains no `secret` column.

**Frontend** (mock `supabase.auth.mfa.*`):
- `client.ts`: a 401 with `code: "mfa_required"` does not sign out and does trigger `onMfaRequired`. A plain 401 still signs out.
- `AuthProvider`: an `aal1`/`aal2` level gives `needs-mfa`, and `ProtectedRoute` redirects to `/verify-2fa`. After verify (an `aal2` session event), the status becomes `ready`.
- `VerifyTwoFactorPage`:
  - The correct code navigates to `from`.
  - A wrong code shows the exact message.
  - With two factors, the device choice sends the chosen `factorId`.
  - Sign out is available.
- `LoginPage`: a half signed-in result navigates to `/verify-2fa`, and a normal result navigates as today.
- `TwoFactorSettings`:
  - Enrolling shows the QR `img` and secret, and confirming with a code calls verify and shows the "on" message.
  - Leftover unverified factors are removed before enrolling.
  - "Add another device" is hidden at 2 verified factors.
  - Remove goes through the confirm dialog, and calls unenroll with that factor.
  - The `aal1` refusal message shows when Supabase rejects the removal.
- `expectNoEmoji()` on the verify page and the settings section.

## 7. Recovery (documented in the dev log)

If a user loses every device, the site owner runs this in the Supabase SQL editor:

```sql
delete from auth.mfa_factors where user_id = '<the user id>';
```

The user can then sign in with their password and set up 2FA again. Within 60 seconds the backend stops asking for a code, because that's the cache TTL.

## 8. Rollout

- In the Supabase dashboard, check that Authentication → Multi-Factor → TOTP is enabled. It's on by default for new projects.
- No migrations.
- Deploy the backend and frontend together, because the client must understand `mfa_required` before the backend sends it.

## 9. Out of scope

- SMS or phone codes, and passkeys/WebAuthn.
- Making 2FA mandatory.
- Self-service backup codes.
- "Remember this device".
- Admin tooling for resets beyond the documented SQL.
