import { ageOn, validateBirthDate, MIN_AGE } from './ageCheck';

const d = (iso: string) => new Date(`${iso}T12:00:00`);

test('MIN_AGE is 13', () => {
  expect(MIN_AGE).toBe(13);
});

test('the day before the 13th birthday gives 12, the birthday itself gives 13', () => {
  expect(ageOn('2012-06-15', d('2025-06-14'))).toBe(12);
  expect(ageOn('2012-06-15', d('2025-06-15'))).toBe(13);
});

test('a 29 Feb birthday is reached on 1 Mar in a non-leap year', () => {
  expect(ageOn('2012-02-29', d('2025-02-28'))).toBe(12);
  expect(ageOn('2012-02-29', d('2025-03-01'))).toBe(13);
});

test('a 29 Feb birthday is reached on 29 Feb in a leap year', () => {
  expect(ageOn('2012-02-29', d('2028-02-28'))).toBe(15);
  expect(ageOn('2012-02-29', d('2028-02-29'))).toBe(16);
});

test('unparseable dates give null', () => {
  expect(ageOn('garbage', d('2025-01-01'))).toBeNull();
  expect(ageOn('', d('2025-01-01'))).toBeNull();
  expect(ageOn('2025-02-30', d('2025-03-01'))).toBeNull();
});

test('validateBirthDate accepts an ordinary date', () => {
  expect(validateBirthDate('1990-05-15', d('2025-06-01'))).toBe('ok');
  expect(validateBirthDate('2025-06-01', d('2025-06-01'))).toBe('ok'); // born today
});

test('a future date is invalid', () => {
  expect(validateBirthDate('2025-06-02', d('2025-06-01'))).toBe('invalid');
});

test('a date 121 years ago is invalid, 120 is still ok', () => {
  expect(validateBirthDate('1904-06-01', d('2025-06-01'))).toBe('invalid');
  expect(validateBirthDate('1905-06-01', d('2025-06-01'))).toBe('ok');
});

test('garbage is invalid', () => {
  expect(validateBirthDate('garbage', d('2025-06-01'))).toBe('invalid');
  expect(validateBirthDate('', d('2025-06-01'))).toBe('invalid');
});
