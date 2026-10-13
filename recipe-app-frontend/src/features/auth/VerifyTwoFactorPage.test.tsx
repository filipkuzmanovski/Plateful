import { MemoryRouter, Route, Routes } from 'react-router';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import VerifyTwoFactorPage from './VerifyTwoFactorPage';
import { expectNoEmoji } from '@/test/noEmoji';

const mocks = vi.hoisted(() => ({
  status: 'needs-mfa' as string,
  authSignOut: vi.fn(async () => undefined),
  listFactors: vi.fn(),
  challenge: vi.fn(),
  verify: vi.fn(),
}));

vi.mock('@/auth/AuthProvider', () => ({
  useAuth: () => ({ status: mocks.status, signOut: mocks.authSignOut }),
}));

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      mfa: { listFactors: mocks.listFactors, challenge: mocks.challenge, verify: mocks.verify },
    },
  },
}));

const WRONG = "That code didn't work. Check your app and try again.";

function renderPage(from: string | null = '/me') {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/verify-2fa', state: from ? { from } : undefined }]}>
      <Routes>
        <Route path="/verify-2fa" element={<VerifyTwoFactorPage />} />
        <Route path="/me" element={<p>my profile page</p>} />
        <Route path="/login" element={<p>login page</p>} />
        <Route path="/" element={<p>home page</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

const factor = (id: string, friendly_name: string) => ({ id, friendly_name, factor_type: 'totp', status: 'verified' });

beforeEach(() => {
  mocks.status = 'needs-mfa';
  mocks.authSignOut.mockClear();
  mocks.listFactors.mockReset();
  mocks.challenge.mockReset();
  mocks.verify.mockReset();
  mocks.listFactors.mockResolvedValue({ data: { all: [], totp: [factor('f1', 'Phone')] }, error: null });
  mocks.challenge.mockResolvedValue({ data: { id: 'c1' }, error: null });
  mocks.verify.mockResolvedValue({ data: {}, error: null });
});

async function enterCode(code = '123456') {
  await userEvent.type(await screen.findByLabelText('Code'), code);
  await userEvent.click(screen.getByRole('button', { name: 'Verify' }));
}

test('correctCodeNavigatesToFrom', async () => {
  renderPage('/me');
  expect(await screen.findByRole('heading', { name: 'Two-factor verification' })).toBeInTheDocument();
  expect(screen.getByText('Enter the 6-digit code from your authenticator app.')).toBeInTheDocument();
  const input = await screen.findByLabelText('Code');
  expect(input).toHaveAttribute('inputmode', 'numeric');
  expect(input).toHaveAttribute('autocomplete', 'one-time-code');
  expect(input).toHaveAttribute('maxlength', '6');
  expect(input).toHaveAttribute('pattern', '\\d{6}');
  expect(screen.queryByText('Which device?')).not.toBeInTheDocument();

  await enterCode();

  expect(await screen.findByText('my profile page')).toBeInTheDocument();
  expect(mocks.challenge).toHaveBeenCalledWith({ factorId: 'f1' });
  expect(mocks.verify).toHaveBeenCalledWith({ factorId: 'f1', challengeId: 'c1', code: '123456' });
  expectNoEmoji();
});

test('withoutFromNavigatesHome', async () => {
  renderPage(null);
  await enterCode();
  expect(await screen.findByText('home page')).toBeInTheDocument();
});

test('wrongCodeShowsMessage', async () => {
  mocks.verify.mockResolvedValue({ data: null, error: { status: 422, message: 'Invalid TOTP code entered' } });
  renderPage();
  await enterCode('000000');

  expect(await screen.findByText(WRONG)).toBeInTheDocument();
  expect(screen.queryByText('my profile page')).not.toBeInTheDocument();
  expectNoEmoji();
});

test('otherErrorsShowSupabaseMessage', async () => {
  mocks.verify.mockResolvedValue({ data: null, error: { status: 500, message: 'Something broke upstream' } });
  renderPage();
  await enterCode();

  expect(await screen.findByText('Something broke upstream')).toBeInTheDocument();
  expect(screen.queryByText(WRONG)).not.toBeInTheDocument();
});

test('twoDevicesChooseFactor', async () => {
  mocks.listFactors.mockResolvedValue({
    data: { all: [], totp: [factor('f1', 'Phone'), factor('f2', 'Tablet')] },
    error: null,
  });
  renderPage();

  expect(await screen.findByRole('group', { name: 'Which device?' })).toBeInTheDocument();
  expect(screen.getByRole('radio', { name: 'Phone' })).toBeChecked();
  await userEvent.click(screen.getByRole('radio', { name: 'Tablet' }));
  await enterCode();

  expect(mocks.challenge).toHaveBeenCalledWith({ factorId: 'f2' });
  expect(mocks.verify).toHaveBeenCalledWith({ factorId: 'f2', challengeId: 'c1', code: '123456' });
});

test('signOutAvailable', async () => {
  renderPage();
  await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
  expect(mocks.authSignOut).toHaveBeenCalled();
});

test('noFactorsShowsMessageAndSignOutOnly', async () => {
  mocks.listFactors.mockResolvedValue({ data: { all: [], totp: [] }, error: null });
  renderPage();

  expect(await screen.findByText('No authenticator found for this account.')).toBeInTheDocument();
  expect(screen.queryByLabelText('Code')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Verify' })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
  expect(mocks.authSignOut).toHaveBeenCalled();
});

test('readyStatusLeavesImmediately', async () => {
  mocks.status = 'ready';
  renderPage('/me');
  expect(await screen.findByText('my profile page')).toBeInTheDocument();
  expect(mocks.listFactors).not.toHaveBeenCalled();
});

test('anonymousStatusGoesToLogin', async () => {
  mocks.status = 'anonymous';
  renderPage('/me');
  expect(await screen.findByText('login page')).toBeInTheDocument();
});

test('loadingStatusShowsSpinner', () => {
  mocks.status = 'loading';
  renderPage();
  expect(screen.getByRole('status')).toBeInTheDocument();
  expect(screen.queryByLabelText('Code')).not.toBeInTheDocument();
});

test('wrongCodeIsDetectedByErrorCode', async () => {
  mocks.verify.mockResolvedValue({ data: null, error: { code: 'mfa_verification_failed', message: 'Verification failed' } });
  renderPage();
  await enterCode('000000');

  expect(await screen.findByText(WRONG)).toBeInTheDocument();
});

test('loadErrorOffersTryAgainAndSignOut', async () => {
  mocks.listFactors.mockResolvedValueOnce({ data: null, error: { message: 'Network down' } });
  renderPage();

  expect(await screen.findByText('Network down')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

  expect(await screen.findByLabelText('Code')).toBeInTheDocument();
  expect(screen.queryByText('Network down')).not.toBeInTheDocument();
  expect(mocks.listFactors).toHaveBeenCalledTimes(2);
});
