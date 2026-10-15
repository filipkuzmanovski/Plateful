import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BirthDateField } from './BirthDateField';
import { validateBirthDate } from './ageCheck';
import { expectNoEmoji } from '@/test/noEmoji';

function Harness({ initial = '', error }: { initial?: string; error?: string }) {
  const [value, setValue] = useState(initial);
  return (
    <>
      <BirthDateField value={value} error={error} onChange={setValue} />
      <output data-testid="value">{value}</output>
    </>
  );
}

const day = () => screen.getByRole('textbox', { name: 'Day' });
const year = () => screen.getByRole('textbox', { name: 'Year' });
const month = () => screen.getByRole('combobox', { name: 'Month' });
const value = () => screen.getByTestId('value').textContent;

async function chooseMonth(name: string) {
  await userEvent.click(month());
  await userEvent.click(await screen.findByRole('option', { name }));
}

test('typingDayMonthYearAssemblesTheValue', async () => {
  render(<Harness />);
  expect(value()).toBe('');
  await userEvent.type(day(), '07');
  await chooseMonth('March');
  expect(value()).toBe('');
  await userEvent.type(year(), '1990');
  expect(value()).toBe('1990-03-07');
});

test('incompleteDatesGiveAnEmptyString', async () => {
  render(<Harness />);
  await userEvent.type(day(), '07');
  await chooseMonth('March');
  await userEvent.type(year(), '199');
  expect(value()).toBe('');
});

test('aSingleDigitDayIsPaddedWhenTheDateIsComplete', async () => {
  render(<Harness />);
  await userEvent.type(day(), '5');
  await chooseMonth('June');
  await userEvent.type(year(), '1985');
  expect(value()).toBe('1985-06-05');
});

test('anImpossibleDateIsNotAcceptedByTheAgeCheck', async () => {
  render(<Harness />);
  await userEvent.type(day(), '31');
  await chooseMonth('February');
  await userEvent.type(year(), '2001');
  expect(validateBirthDate(value() ?? '', new Date('2026-10-13T12:00:00'))).toBe('invalid');
});

test('onlyDigitsAreKeptAndLengthIsLimited', async () => {
  render(<Harness />);
  await userEvent.type(day(), 'a1b2c3');
  expect(day()).toHaveValue('12');
  await userEvent.type(year(), '19x9y05');
  expect(year()).toHaveValue('1990');
});

test('dayAdvancesToMonthAfterTwoDigits', async () => {
  render(<Harness />);
  await userEvent.type(day(), '12');
  expect(month()).toHaveFocus();
});

test('dayAdvancesToMonthAfterOneDigitAboveThree', async () => {
  render(<Harness />);
  await userEvent.type(day(), '7');
  expect(month()).toHaveFocus();
});

test('dayStaysPutAfterOneDigitUpToThree', async () => {
  render(<Harness />);
  await userEvent.type(day(), '3');
  expect(day()).toHaveFocus();
});

test('choosingAMonthMovesFocusToYear', async () => {
  render(<Harness />);
  await chooseMonth('July');
  expect(year()).toHaveFocus();
  expect(month()).toHaveTextContent('July');
});

test('choosingTheAlreadySelectedMonthStillMovesFocusToYear', async () => {
  render(<Harness initial="1990-03-07" />);
  await chooseMonth('March');
  expect(year()).toHaveFocus();
  expect(month()).toHaveTextContent('March');
});

test('escapingTheMonthMenuKeepsFocusOnMonth', async () => {
  render(<Harness />);
  await userEvent.click(month());
  await screen.findByRole('option', { name: 'May' });
  await userEvent.keyboard('{Escape}');
  expect(month()).toHaveFocus();
});

test('typeaheadOnTheClosedMonthTriggerDoesNotSendALaterEscapeToYear', async () => {
  render(<Harness />);
  month().focus();
  await userEvent.keyboard('m');
  expect(month()).toHaveTextContent(/March|May/);
  await userEvent.keyboard('{Enter}');
  await screen.findByRole('option', { name: 'May' });
  await userEvent.keyboard('{Escape}');
  expect(month()).toHaveFocus();
});

test('backspaceInAnEmptyYearGoesBackToMonth', async () => {
  render(<Harness />);
  await chooseMonth('July');
  await userEvent.type(year(), '1');
  await userEvent.keyboard('{Backspace}');
  expect(year()).toHaveValue('');
  expect(year()).toHaveFocus();
  await userEvent.keyboard('{Backspace}');
  expect(month()).toHaveFocus();
});

test('backspaceOnTheMonthGoesBackToDay', async () => {
  render(<Harness />);
  await userEvent.type(day(), '12');
  expect(month()).toHaveFocus();
  await userEvent.keyboard('{Backspace}');
  expect(day()).toHaveFocus();
});

test('monthMenuListsTwelveMonthsInOrderAndMarksTheChosenOne', async () => {
  render(<Harness initial="1990-03-07" />);
  await userEvent.click(month());
  const options = await screen.findAllByRole('option');
  expect(options.map((o) => o.textContent)).toEqual([
    'January', 'February', 'March', 'April', 'May', 'June',
    'July', 'August', 'September', 'October', 'November', 'December',
  ]);
  expect(screen.getByRole('option', { name: 'March' })).toHaveAttribute('aria-selected', 'true');
  expectNoEmoji();
});

test('anExistingValueFillsTheParts', () => {
  render(<Harness initial="1990-03-07" />);
  expect(day()).toHaveValue('07');
  expect(month()).toHaveTextContent('March');
  expect(year()).toHaveValue('1990');
});

test('thePartsHaveAccessibleNamesInsideALabelledGroup', () => {
  render(<Harness />);
  const group = screen.getByRole('group', { name: 'Date of birth' });
  expect(group).toContainElement(day());
  expect(group).toContainElement(month());
  expect(group).toContainElement(year());
  expect(day()).toHaveAttribute('inputmode', 'numeric');
  expect(year()).toHaveAttribute('inputmode', 'numeric');
  expect(day()).toHaveAttribute('placeholder', 'DD');
  expect(year()).toHaveAttribute('placeholder', 'YYYY');
  expectNoEmoji();
});

test('theErrorIsDescribedOnTheGroupOnlyAndThePartsAreInvalid', () => {
  render(<Harness error="Something is off" />);
  const alert = screen.getByRole('alert');
  expect(alert).toHaveTextContent('Something is off');
  expect(screen.getByRole('group', { name: 'Date of birth' })).toHaveAttribute('aria-describedby', alert.id);
  for (const el of [day(), month(), year()]) expect(el).not.toHaveAttribute('aria-describedby');
  for (const el of [day(), month(), year()]) expect(el).toHaveAttribute('aria-invalid', 'true');
});

test('withoutAnErrorNothingIsInvalid', () => {
  render(<Harness />);
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  expect(day()).not.toHaveAttribute('aria-invalid');
  expect(day()).not.toHaveAttribute('aria-describedby');
});
