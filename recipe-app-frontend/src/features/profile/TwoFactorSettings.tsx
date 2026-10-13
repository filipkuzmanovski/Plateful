import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { supabase } from '@/auth/supabaseClient';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { Button, Field, Pill, Spinner, TextInput } from '@/components/ui';
import { FormError } from '@/features/auth/AuthCard';

const WRONG_CODE = "That code didn't work. Check your app and try again.";
const AAL1_REFUSAL = 'Please sign in again with your code to make this change.';
const MAX_DEVICES = 2;

interface Factor {
  id: string;
  friendly_name?: string;
  factor_type?: string;
  status?: string;
  created_at?: string;
}

interface Pending {
  factorId: string;
  qr: string;
  secret: string;
}

type Step = { kind: 'idle' } | { kind: 'name' } | { kind: 'scan'; pending: Pending };

function errorInfo(err: unknown): { status?: number; message: string } {
  const { status, message } = (err ?? {}) as { status?: number; message?: string };
  return { status, message: message || 'Something went wrong. Please try again.' };
}

function isAal2Refusal(err: unknown): boolean {
  const { status, message } = errorInfo(err);
  return status === 403 || /aal2/i.test(message);
}

function addedOn(createdAt?: string): string | null {
  if (!createdAt) return null;
  const d = new Date(createdAt);
  return Number.isNaN(d.getTime()) ? null : d.toLocaleDateString(undefined, { dateStyle: 'medium' });
}

/** Device names must be unique per user, so skip any "Authenticator n" that is already taken. */
function defaultName(factors: Factor[]): string {
  const taken = new Set(factors.map((f) => f.friendly_name));
  for (let n = factors.length + 1; ; n++) {
    const name = `Authenticator ${n}`;
    if (!taken.has(name)) return name;
  }
}

/** Turn two-factor authentication on, add or remove authenticator devices. Reads Supabase directly. */
export function TwoFactorSettings() {
  const [factors, setFactors] = useState<Factor[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [step, setStep] = useState<Step>({ kind: 'idle' });
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [justEnabled, setJustEnabled] = useState(false);
  const [removeTarget, setRemoveTarget] = useState<Factor | null>(null);
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  const refresh = useCallback(async () => {
    try {
      const { data, error: listError } = await supabase.auth.mfa.listFactors();
      if (!alive.current) return;
      if (listError) throw listError;
      setFactors((data?.totp ?? []) as Factor[]);
      setLoadError(null);
    } catch (err) {
      if (!alive.current) return;
      setFactors((prev) => prev ?? []);
      setLoadError(errorInfo(err).message);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  if (factors === null) return <Spinner />;

  const count = factors.length;
  const canAdd = count < MAX_DEVICES && step.kind === 'idle';

  function startAdd() {
    setError(null);
    setJustEnabled(false);
    setName(defaultName(factors ?? []));
    setStep({ kind: 'name' });
  }

  async function onContinue(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    const friendlyName = name.trim();
    if (!friendlyName) return;
    setBusy(true);
    setError(null);
    try {
      // Supabase keeps unverified factors from an abandoned setup, and rejects a duplicate name: clear them first.
      const { data: listed, error: listError } = await supabase.auth.mfa.listFactors();
      if (listError) throw listError;
      const leftovers = ((listed?.all ?? []) as Factor[]).filter(
        (f) => f.factor_type === 'totp' && f.status === 'unverified',
      );
      for (const f of leftovers) {
        const { error: unenrollError } = await supabase.auth.mfa.unenroll({ factorId: f.id });
        if (unenrollError) throw unenrollError;
      }
      const { data, error: enrollError } = await supabase.auth.mfa.enroll({ factorType: 'totp', friendlyName });
      if (enrollError || !data) throw enrollError ?? new Error('Could not start setup.');
      if (!alive.current) return;
      setCode('');
      setStep({ kind: 'scan', pending: { factorId: data.id, qr: data.totp.qr_code, secret: data.totp.secret } });
    } catch (err) {
      if (alive.current) setError(errorInfo(err).message);
    } finally {
      if (alive.current) setBusy(false);
    }
  }

  async function onConfirm(e: FormEvent) {
    e.preventDefault();
    if (busy || step.kind !== 'scan') return;
    const { factorId } = step.pending;
    setBusy(true);
    setError(null);
    try {
      const { data: challenge, error: challengeError } = await supabase.auth.mfa.challenge({ factorId });
      if (challengeError || !challenge) throw challengeError ?? new Error('Could not start verification.');
      const { error: verifyError } = await supabase.auth.mfa.verify({ factorId, challengeId: challenge.id, code });
      if (verifyError) throw verifyError;
      if (!alive.current) return;
      setStep({ kind: 'idle' });
      setCode('');
      setJustEnabled(true);
      await refresh();
    } catch (err) {
      if (!alive.current) return;
      const { status, message } = errorInfo(err);
      setError(status === 422 || /code/i.test(message) ? WRONG_CODE : message);
      setCode('');
    } finally {
      if (alive.current) setBusy(false);
    }
  }

  async function onCancel() {
    if (busy) return;
    const pending = step.kind === 'scan' ? step.pending : null;
    setError(null);
    setCode('');
    setStep({ kind: 'idle' });
    if (!pending) return;
    try {
      await supabase.auth.mfa.unenroll({ factorId: pending.factorId });
    } catch {
      // The leftover is removed at the next setup.
    }
  }

  async function onRemove() {
    if (!removeTarget || busy) return;
    setBusy(true);
    setError(null);
    try {
      const { error: unenrollError } = await supabase.auth.mfa.unenroll({ factorId: removeTarget.id });
      if (unenrollError) throw unenrollError;
      if (!alive.current) return;
      setRemoveTarget(null);
      setJustEnabled(false);
      await refresh();
    } catch (err) {
      if (!alive.current) return;
      setRemoveTarget(null);
      setError(isAal2Refusal(err) ? AAL1_REFUSAL : errorInfo(err).message);
    } finally {
      if (alive.current) setBusy(false);
    }
  }

  const removeLabel = (f: Factor) => f.friendly_name || 'Authenticator';

  return (
    <div>
      <p className="text-sm text-olive/70">
        Ask for a 6-digit code from an authenticator app each time you sign in.
      </p>
      <p className="mt-4 flex items-center gap-2 text-sm font-semibold text-olive">
        Status
        <Pill tone={count > 0 ? 'olive' : 'sand'}>
          {count === 0 ? 'Off' : `On: ${count} ${count === 1 ? 'device' : 'devices'}`}
        </Pill>
      </p>

      {justEnabled && (
        <p role="status" className="mt-4 rounded-xl bg-olive/10 px-3.5 py-2.5 text-sm font-medium text-olive">
          Two-factor authentication is on
        </p>
      )}
      {loadError && <div className="mt-4"><FormError>{loadError}</FormError></div>}

      {count > 0 && (
        <ul className="mt-4 divide-y divide-sand/70 rounded-xl ring-1 ring-sand/70">
          {factors.map((f) => {
            const added = addedOn(f.created_at);
            return (
              <li key={f.id} className="flex items-center justify-between gap-3 px-4 py-3">
                <div className="min-w-0">
                  <p className="truncate text-sm font-semibold text-olive">{removeLabel(f)}</p>
                  {added && <p className="text-xs text-olive/70">Added {added}</p>}
                </div>
                <Button
                  type="button"
                  variant="danger"
                  aria-label={`Remove ${removeLabel(f)}`}
                  onClick={() => { setError(null); setRemoveTarget(f); }}
                  className="px-4 py-1.5"
                >
                  Remove
                </Button>
              </li>
            );
          })}
        </ul>
      )}

      {step.kind === 'name' && (
        <form onSubmit={onContinue} className="mt-5 max-w-sm space-y-4">
          <Field label="Device name">
            <TextInput value={name} onChange={(e) => setName(e.target.value)} required maxLength={64} autoFocus />
          </Field>
          {error && <FormError>{error}</FormError>}
          <div className="flex gap-2">
            <Button type="submit" disabled={busy}>Continue</Button>
            <Button type="button" variant="ghost" onClick={() => { setError(null); setStep({ kind: 'idle' }); }}>
              Cancel
            </Button>
          </div>
        </form>
      )}

      {step.kind === 'scan' && (
        <form onSubmit={onConfirm} className="mt-5 max-w-sm space-y-4">
          <p className="text-sm text-olive/70">
            Scan this QR code with your authenticator app, then enter the 6-digit code it shows.
          </p>
          <div className="inline-block rounded-2xl bg-paper p-4 ring-1 ring-sand">
            <img src={step.pending.qr} alt="QR code for your authenticator app" className="size-44" />
          </div>
          <div>
            <p className="text-[13px] font-semibold text-olive">Can't scan? Enter this key</p>
            <p className="mt-1 font-mono text-sm tracking-wider break-all text-olive select-all">
              {step.pending.secret}
            </p>
          </div>
          <Field label="Code">
            <TextInput
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              pattern="\d{6}"
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
              required
              autoFocus
            />
          </Field>
          {error && <FormError>{error}</FormError>}
          <div className="flex gap-2">
            <Button type="submit" disabled={busy}>Confirm</Button>
            <Button type="button" variant="ghost" onClick={() => void onCancel()} disabled={busy}>Cancel</Button>
          </div>
        </form>
      )}

      {step.kind === 'idle' && error && <div className="mt-4"><FormError>{error}</FormError></div>}

      {canAdd && (
        <div className="mt-5">
          <Button type="button" variant={count === 0 ? 'primary' : 'outline'} onClick={startAdd}>
            {count === 0 ? 'Turn on two-factor' : 'Add another device'}
          </Button>
        </div>
      )}

      <ConfirmDialog
        open={removeTarget !== null}
        title={removeTarget ? `Remove ${removeLabel(removeTarget)}?` : 'Remove device?'}
        description={
          count <= 1
            ? 'You will no longer be asked for a code from this device. This turns two-factor authentication off.'
            : 'You will no longer be asked for a code from this device. Your other device keeps working.'
        }
        confirmLabel="Remove"
        busy={busy}
        onConfirm={() => void onRemove()}
        onCancel={() => setRemoveTarget(null)}
      />
    </div>
  );
}
