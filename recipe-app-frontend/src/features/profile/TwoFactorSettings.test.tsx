import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TwoFactorSettings } from './TwoFactorSettings';
import { expectNoEmoji } from '@/test/noEmoji';

const mocks = vi.hoisted(() => ({
  listFactors: vi.fn(),
  enroll: vi.fn(),
  challenge: vi.fn(),
  verify: vi.fn(),
  unenroll: vi.fn(),
}));

vi.mock('@/auth/supabaseClient', () => ({
  supabase: { auth: { mfa: mocks } },
}));

const WRONG = "That code didn't work. Check your app and try again.";
const AAL1 = 'Please sign in again with your code to make this change.';

const verified = (id: string, friendly_name: string) => ({
  id,
  friendly_name,
  factor_type: 'totp',
  status: 'verified',
  created_at: '2026-03-04T10:00:00Z',
});

function listing(totp: ReturnType<typeof verified>[], extraAll: object[] = []) {
  return { data: { totp, all: [...totp, ...extraAll] }, error: null };
}

beforeEach(() => {
  Object.values(mocks).forEach((m) => m.mockReset());
  mocks.listFactors.mockResolvedValue(listing([]));
  mocks.enroll.mockResolvedValue({
    data: { id: 'new1', totp: { qr_code: 'data:image/svg+xml;utf8,<svg/>', secret: 'JBSWY3DPEHPK3PXP', uri: 'otpauth://x' } },
    error: null,
  });
  mocks.challenge.mockResolvedValue({ data: { id: 'c1' }, error: null });
  mocks.verify.mockResolvedValue({ data: {}, error: null });
  mocks.unenroll.mockResolvedValue({ data: {}, error: null });
});

test('enrollShowsQrAndSecretThenConfirms', async () => {
  render(<TwoFactorSettings />);
  expect(await screen.findByText('Off')).toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: 'Turn on two-factor' }));
  expect(screen.getByLabelText('Device name')).toHaveValue('Authenticator 1');
  await userEvent.click(screen.getByRole('button', { name: 'Continue' }));

  const qr = await screen.findByRole('img', { name: 'QR code for your authenticator app' });
  expect(qr).toHaveAttribute('src', 'data:image/svg+xml;utf8,<svg/>');
  expect(screen.getByText("Can't scan? Enter this key")).toBeInTheDocument();
  expect(screen.getByText('JBSWY3DPEHPK3PXP')).toBeInTheDocument();
  expect(mocks.enroll).toHaveBeenCalledWith({ factorType: 'totp', friendlyName: 'Authenticator 1' });

  const input = screen.getByLabelText('Code');
  expect(input).toHaveAttribute('inputmode', 'numeric');
  expect(input).toHaveAttribute('autocomplete', 'one-time-code');
  expect(input).toHaveAttribute('maxlength', '6');
  expect(input).toHaveAttribute('pattern', '\\d{6}');

  mocks.listFactors.mockResolvedValue(listing([verified('new1', 'Authenticator 1')]));
  await userEvent.type(input, '123456');
  await userEvent.click(screen.getByRole('button', { name: 'Confirm' }));

  expect(await screen.findByText('Two-factor authentication is on')).toBeInTheDocument();
  expect(mocks.challenge).toHaveBeenCalledWith({ factorId: 'new1' });
  expect(mocks.verify).toHaveBeenCalledWith({ factorId: 'new1', challengeId: 'c1', code: '123456' });
  expect(await screen.findByText('On: 1 device')).toBeInTheDocument();
  expect(screen.queryByRole('img', { name: 'QR code for your authenticator app' })).not.toBeInTheDocument();
  expectNoEmoji();
});

test('wrongCodeShowsMessage', async () => {
  mocks.verify.mockResolvedValue({ data: null, error: { status: 422, message: 'Invalid TOTP code entered' } });
  render(<TwoFactorSettings />);
  await userEvent.click(await screen.findByRole('button', { name: 'Turn on two-factor' }));
  await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
  await userEvent.type(await screen.findByLabelText('Code'), '000000');
  await userEvent.click(screen.getByRole('button', { name: 'Confirm' }));

  expect(await screen.findByText(WRONG)).toBeInTheDocument();
  expect(screen.queryByText('Two-factor authentication is on')).not.toBeInTheDocument();
  expectNoEmoji();
});

test('removesLeftoverUnverifiedBeforeEnroll', async () => {
  const order: string[] = [];
  mocks.listFactors.mockResolvedValue(
    listing([], [{ id: 'x', friendly_name: 'Old', factor_type: 'totp', status: 'unverified', created_at: '2026-01-01T00:00:00Z' }]),
  );
  mocks.unenroll.mockImplementation(async () => { order.push('unenroll'); return { data: {}, error: null }; });
  mocks.enroll.mockImplementation(async () => {
    order.push('enroll');
    return { data: { id: 'new1', totp: { qr_code: 'data:image/svg+xml;utf8,<svg/>', secret: 'S', uri: 'u' } }, error: null };
  });

  render(<TwoFactorSettings />);
  await userEvent.click(await screen.findByRole('button', { name: 'Turn on two-factor' }));
  await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
  await screen.findByRole('img', { name: 'QR code for your authenticator app' });

  expect(mocks.unenroll).toHaveBeenCalledWith({ factorId: 'x' });
  expect(order).toEqual(['unenroll', 'enroll']);
});

test('hidesAddAtTwoDevices', async () => {
  mocks.listFactors.mockResolvedValue(listing([verified('f1', 'Phone'), verified('f2', 'Tablet')]));
  render(<TwoFactorSettings />);

  expect(await screen.findByText('On: 2 devices')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Add another device' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Turn on two-factor' })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Remove Phone' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Remove Tablet' })).toBeInTheDocument();
});

test('oneDeviceOffersAddAnotherWithNextDefaultName', async () => {
  mocks.listFactors.mockResolvedValue(listing([verified('f1', 'Phone')]));
  render(<TwoFactorSettings />);

  expect(await screen.findByText('On: 1 device')).toBeInTheDocument();
  expect(screen.getByText('Phone')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Add another device' }));
  expect(screen.getByLabelText('Device name')).toHaveValue('Authenticator 2');
});

test('removeGoesThroughConfirm', async () => {
  mocks.listFactors.mockResolvedValue(listing([verified('f1', 'Phone'), verified('f2', 'Tablet')]));
  render(<TwoFactorSettings />);

  await userEvent.click(await screen.findByRole('button', { name: 'Remove Phone' }));
  expect(mocks.unenroll).not.toHaveBeenCalled();
  const dialog = await screen.findByRole('dialog');
  expect(within(dialog).queryByText(/This turns two-factor authentication off\./)).not.toBeInTheDocument();

  mocks.listFactors.mockResolvedValue(listing([verified('f2', 'Tablet')]));
  await userEvent.click(within(dialog).getByRole('button', { name: 'Remove' }));

  expect(mocks.unenroll).toHaveBeenCalledWith({ factorId: 'f1' });
  expect(await screen.findByText('On: 1 device')).toBeInTheDocument();
});

test('removingLastDeviceWarnsItTurnsOff', async () => {
  mocks.listFactors.mockResolvedValue(listing([verified('f1', 'Phone')]));
  render(<TwoFactorSettings />);

  await userEvent.click(await screen.findByRole('button', { name: 'Remove Phone' }));
  const dialog = await screen.findByRole('dialog');
  expect(within(dialog).getByText(/This turns two-factor authentication off\./)).toBeInTheDocument();
});

test('removeRefusedAtAal1ShowsMessage', async () => {
  mocks.listFactors.mockResolvedValue(listing([verified('f1', 'Phone')]));
  mocks.unenroll.mockResolvedValue({ data: null, error: { status: 403, message: 'AAL2 required' } });
  render(<TwoFactorSettings />);

  await userEvent.click(await screen.findByRole('button', { name: 'Remove Phone' }));
  await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Remove' }));

  expect(await screen.findByText(AAL1)).toBeInTheDocument();
  expect(screen.getByText('On: 1 device')).toBeInTheDocument();
  expectNoEmoji();
});

test('cancelUnenrollsPending', async () => {
  render(<TwoFactorSettings />);
  await userEvent.click(await screen.findByRole('button', { name: 'Turn on two-factor' }));
  await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
  await screen.findByRole('img', { name: 'QR code for your authenticator app' });

  await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

  expect(mocks.unenroll).toHaveBeenCalledWith({ factorId: 'new1' });
  expect(screen.queryByRole('img', { name: 'QR code for your authenticator app' })).not.toBeInTheDocument();
  expect(await screen.findByRole('button', { name: 'Turn on two-factor' })).toBeInTheDocument();
});

test('wrongCodeIsDetectedByErrorCode', async () => {
  mocks.verify.mockResolvedValue({ data: null, error: { code: 'mfa_verification_failed', message: 'Verification failed' } });
  render(<TwoFactorSettings />);
  await userEvent.click(await screen.findByRole('button', { name: 'Turn on two-factor' }));
  await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
  await userEvent.type(await screen.findByLabelText('Code'), '000000');
  await userEvent.click(screen.getByRole('button', { name: 'Confirm' }));

  expect(await screen.findByText(WRONG)).toBeInTheDocument();
});

test('aLoadErrorShowsRetryAndNeverOffOrTurnOn', async () => {
  mocks.listFactors.mockResolvedValue({ data: null, error: { message: 'Network down' } });
  render(<TwoFactorSettings />);

  expect(await screen.findByText('Network down')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  expect(screen.queryByText('Off')).not.toBeInTheDocument();
  expect(screen.queryByText(/^On:/)).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Turn on two-factor' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Add another device' })).not.toBeInTheDocument();
  expectNoEmoji();
});

test('tryAgainReloadsAndShowsTheRealStatus', async () => {
  mocks.listFactors.mockResolvedValueOnce({ data: null, error: { message: 'Network down' } });
  mocks.listFactors.mockResolvedValue(listing([verified('f1', 'Phone')]));
  render(<TwoFactorSettings />);

  await userEvent.click(await screen.findByRole('button', { name: 'Try again' }));

  expect(await screen.findByText('On: 1 device')).toBeInTheDocument();
  expect(screen.queryByText('Network down')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  expect(mocks.listFactors).toHaveBeenCalledTimes(2);
});

test('aFailedReloadAfterConfirmKeepsTheSuccessMessageAndNeverShowsOff', async () => {
  render(<TwoFactorSettings />);
  await userEvent.click(await screen.findByRole('button', { name: 'Turn on two-factor' }));
  await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
  await userEvent.type(await screen.findByLabelText('Code'), '123456');

  mocks.listFactors.mockResolvedValue({ data: null, error: { message: 'Network down' } });
  await userEvent.click(screen.getByRole('button', { name: 'Confirm' }));

  expect(await screen.findByText('Network down')).toBeInTheDocument();
  expect(screen.getByText('Two-factor authentication is on')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  expect(screen.queryByText('Off')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Turn on two-factor' })).not.toBeInTheDocument();
});

test('continueStopsWhenTheFreshListAlreadyHasTwoDevices', async () => {
  render(<TwoFactorSettings />);
  await userEvent.click(await screen.findByRole('button', { name: 'Turn on two-factor' }));

  mocks.listFactors.mockResolvedValue(listing([verified('f1', 'Phone'), verified('f2', 'Tablet')]));
  await userEvent.click(screen.getByRole('button', { name: 'Continue' }));

  expect(await screen.findByText('You already have 2 devices. Remove one to add another.')).toBeInTheDocument();
  expect(mocks.enroll).not.toHaveBeenCalled();
  expect(mocks.unenroll).not.toHaveBeenCalled();
});
