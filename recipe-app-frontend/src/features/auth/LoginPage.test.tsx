import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import LoginPage from './LoginPage';
import { expectNoEmoji } from '@/test/noEmoji';

const mocks = vi.hoisted(() => ({
  signInWithPassword: vi.fn(),
  getAal: vi.fn(),
}));

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      signInWithPassword: mocks.signInWithPassword,
      mfa: { getAuthenticatorAssuranceLevel: mocks.getAal },
    },
  },
}));

function VerifyProbe() {
  const location = useLocation();
  return <p>verify page {JSON.stringify(location.state)}</p>;
}

function renderLogin(from?: string) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/login', state: from ? { from } : undefined }]}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/verify-2fa" element={<VerifyProbe />} />
        <Route path="/me" element={<p>my profile page</p>} />
        <Route path="/" element={<p>home page</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

async function submit() {
  await userEvent.type(screen.getByLabelText('Email'), 'jane@example.com');
  await userEvent.type(screen.getByLabelText('Password'), 'secret123');
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

beforeEach(() => {
  mocks.signInWithPassword.mockReset();
  mocks.getAal.mockReset();
  mocks.signInWithPassword.mockResolvedValue({ data: {}, error: null });
  mocks.getAal.mockResolvedValue({ data: { currentLevel: 'aal1', nextLevel: 'aal1' }, error: null });
});

test('loginRedirectsWhenHalfSignedIn', async () => {
  mocks.getAal.mockResolvedValue({ data: { currentLevel: 'aal1', nextLevel: 'aal2' }, error: null });
  renderLogin('/me');
  await submit();
  // the code step must still know where to send the user afterwards
  expect(await screen.findByText(/verify page/)).toHaveTextContent('{"from":"/me"}');
  expectNoEmoji();
});

test('normalLoginGoesHome', async () => {
  renderLogin();
  await submit();
  expect(await screen.findByText('home page')).toBeInTheDocument();
});

test('normalLoginGoesToFrom', async () => {
  renderLogin('/me');
  await submit();
  expect(await screen.findByText('my profile page')).toBeInTheDocument();
});

test('aalLookupFailureFallsBackToNormalNavigation', async () => {
  mocks.getAal.mockRejectedValue(new Error('network'));
  renderLogin();
  await submit();
  expect(await screen.findByText('home page')).toBeInTheDocument();
});

test('signInErrorStaysOnPage', async () => {
  mocks.signInWithPassword.mockResolvedValue({ data: {}, error: { message: 'Invalid login credentials' } });
  renderLogin();
  await submit();
  expect(await screen.findByText('Invalid login credentials')).toBeInTheDocument();
  expect(mocks.getAal).not.toHaveBeenCalled();
});
