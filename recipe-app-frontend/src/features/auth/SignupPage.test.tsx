import { MemoryRouter } from 'react-router';
import { render, screen, waitFor, within } from '@testing-library/react';
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

const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

/** Types a YYYY-MM-DD date into the Day / Month / Year parts. */
async function enterDob(dob: string) {
  const [y, m, d] = dob.split('-');
  const day = screen.getByRole('textbox', { name: 'Day' });
  const year = screen.getByRole('textbox', { name: 'Year' });
  await userEvent.clear(day);
  await userEvent.type(day, d);
  await userEvent.click(screen.getByRole('combobox', { name: 'Month' }));
  await userEvent.click(await screen.findByRole('option', { name: MONTHS[Number(m) - 1] }));
  await userEvent.clear(year);
  await userEvent.type(year, y);
}

async function fillAndSubmit(dob: string) {
  await userEvent.type(screen.getByLabelText('Email'), 'jane@example.com');
  await enterDob(dob);
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

  const alert = await screen.findByText(BLOCKED);
  expect(alert).toHaveAttribute('role', 'alert');
  expect(screen.getByRole('group', { name: 'Date of birth' })).toHaveAttribute('aria-describedby', alert.id);
  expect(signUp).not.toHaveBeenCalled();
  // The form stays visible and editable: no lockout.
  expect(screen.getByRole('button', { name: 'Sign up' })).toBeInTheDocument();
  expect(screen.getByLabelText('Email')).toBeEnabled();
  expect(sessionStorage.getItem('plateful.ageBlocked')).toBeNull();
  expectNoEmoji();
});

test('canCorrectDateImmediately', async () => {
  renderSignup();
  await fillAndSubmit(MINOR_DOB);
  expect(await screen.findByText(BLOCKED)).toBeInTheDocument();

  await enterDob(ADULT_DOB);
  expect(screen.queryByText(BLOCKED)).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Sign up' }));

  await waitFor(() => expect(signUp).toHaveBeenCalledTimes(1));
  expect(await screen.findByText(/check your email/i)).toBeInTheDocument();
});

test('staleBlockKeyIsCleared', () => {
  sessionStorage.setItem('plateful.ageBlocked', '1');
  renderSignup();
  expect(screen.getByRole('group', { name: 'Date of birth' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Sign up' })).toBeInTheDocument();
  expect(screen.queryByText(BLOCKED)).not.toBeInTheDocument();
  expect(sessionStorage.getItem('plateful.ageBlocked')).toBeNull();
});

test('unavailableStorageDoesNotBreakThePage', async () => {
  const removeItem = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
    throw new Error('denied');
  });
  try {
    renderSignup();
    expect(screen.getByRole('button', { name: 'Sign up' })).toBeInTheDocument();
    await fillAndSubmit(MINOR_DOB);
    expect(await screen.findByText(BLOCKED)).toBeInTheDocument();
    expect(signUp).not.toHaveBeenCalled();
  } finally {
    removeItem.mockRestore();
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
  const form = screen.getByRole('group', { name: 'Date of birth' }).closest('form')!;
  expect(form.textContent).toContain('Date of birth');
  expect(form.textContent).not.toMatch(/13|thirteen|age/i);
  expectNoEmoji();
});

test('existingAccountShowsSignInLink', async () => {
  signUp.mockResolvedValue({ data: { user: { identities: [] }, session: null }, error: null });
  renderSignup();
  await fillAndSubmit(ADULT_DOB);

  expect(await screen.findByText('An account with this email already exists.')).toBeInTheDocument();
  expect(within(screen.getByRole('status')).getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login');
  expect(screen.queryByText(/check your email/i)).not.toBeInTheDocument();
  expectNoEmoji();
});

test('newAccountWithIdentitiesStillAsksToConfirmEmail', async () => {
  signUp.mockResolvedValue({
    data: { user: { identities: [{ id: 'i-1' }] }, session: null },
    error: null,
  });
  renderSignup();
  await fillAndSubmit(ADULT_DOB);

  expect(await screen.findByText(/check your email/i)).toBeInTheDocument();
  expect(screen.queryByText('An account with this email already exists.')).not.toBeInTheDocument();
});
