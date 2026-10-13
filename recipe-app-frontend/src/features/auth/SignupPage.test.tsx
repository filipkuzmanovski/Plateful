import { MemoryRouter } from 'react-router';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SignupPage from './SignupPage';
import { expectNoEmoji } from '@/test/noEmoji';

const { signUp } = vi.hoisted(() => ({ signUp: vi.fn() }));

vi.mock('@/auth/supabaseClient', () => ({
  supabase: { auth: { signUp } },
}));

const BLOCKED = 'Sorry, you need to be at least 13 to create a Plateful account.';
const ADULT_DOB = '1990-05-15';
const MINOR_DOB = '2015-01-01'; // 11 on the fixed "today"
const FUTURE_DOB = '2030-01-01';

function renderSignup() {
  return render(
    <MemoryRouter initialEntries={['/signup']}>
      <SignupPage />
    </MemoryRouter>,
  );
}

async function fillAndSubmit(dob: string) {
  await userEvent.type(screen.getByLabelText('Email'), 'jane@example.com');
  fireEvent.change(screen.getByLabelText('Date of birth'), { target: { value: dob } });
  await userEvent.type(screen.getByLabelText('Password'), 'secret123');
  await userEvent.click(screen.getByRole('button', { name: 'Sign up' }));
}

beforeEach(() => {
  // Fake only Date so userEvent / waitFor timers keep working.
  vi.useFakeTimers({ toFake: ['Date'], now: new Date('2026-10-13T12:00:00') });
  signUp.mockReset();
  signUp.mockResolvedValue({ data: { session: null, user: null }, error: null });
  sessionStorage.clear();
});

afterEach(() => {
  vi.useRealTimers();
  sessionStorage.clear();
});

test('adultSignupSendsAgeConfirmedNotBirthDate', async () => {
  renderSignup();
  await fillAndSubmit(ADULT_DOB);

  await waitFor(() => expect(signUp).toHaveBeenCalledTimes(1));
  const arg = signUp.mock.calls[0][0];
  expect(arg.email).toBe('jane@example.com');
  expect(arg.password).toBe('secret123');
  const stamp = arg.options.data.age_confirmed_at as string;
  expect(new Date(stamp).toISOString()).toBe(stamp);
  const json = JSON.stringify(arg);
  expect(json).not.toContain(ADULT_DOB);
  expect(json).not.toContain('1990');
  expect(await screen.findByText(/check your email/i)).toBeInTheDocument();
  expectNoEmoji();
});

test('underThirteenIsBlockedAndNothingSent', async () => {
  renderSignup();
  await fillAndSubmit(MINOR_DOB);

  expect(await screen.findByText(BLOCKED)).toBeInTheDocument();
  expect(signUp).not.toHaveBeenCalled();
  expect(screen.queryByRole('button', { name: 'Sign up' })).not.toBeInTheDocument();
  expect(sessionStorage.getItem('plateful.ageBlocked')).toBe('1');
  expectNoEmoji();
});

test('blockPersistsInTab', async () => {
  const first = renderSignup();
  await fillAndSubmit(MINOR_DOB);
  expect(await screen.findByText(BLOCKED)).toBeInTheDocument();
  first.unmount();

  renderSignup();
  expect(screen.getByText(BLOCKED)).toBeInTheDocument();
  expect(screen.queryByLabelText('Date of birth')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Sign up' })).not.toBeInTheDocument();
  expect(signUp).not.toHaveBeenCalled();
  expectNoEmoji();
});

test('blockStillAppliesInMemoryWhenStorageIsUnavailable', async () => {
  const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new Error('denied');
  });
  const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new Error('denied');
  });
  try {
    renderSignup();
    await fillAndSubmit(MINOR_DOB);
    expect(await screen.findByText(BLOCKED)).toBeInTheDocument();
    expect(signUp).not.toHaveBeenCalled();
  } finally {
    setItem.mockRestore();
    getItem.mockRestore();
  }
});

test('invalidDateRejected', async () => {
  renderSignup();
  await fillAndSubmit(FUTURE_DOB);

  expect(await screen.findByText('Please enter a valid date of birth')).toBeInTheDocument();
  expect(signUp).not.toHaveBeenCalled();
  expect(screen.queryByText(BLOCKED)).not.toBeInTheDocument();
  expect(sessionStorage.getItem('plateful.ageBlocked')).toBeNull();
});

test('labelIsNeutral', () => {
  renderSignup();
  const form = screen.getByLabelText('Date of birth').closest('form')!;
  expect(form.textContent).toContain('Date of birth');
  expect(form.textContent).not.toMatch(/13|thirteen|age/i);
  expectNoEmoji();
});
