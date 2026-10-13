/** Minimum age to create a Plateful account. */
export const MIN_AGE = 13;

/** Oldest plausible age; anything beyond this is treated as a typo. */
const MAX_AGE = 120;

/**
 * Whole years between `birth` (YYYY-MM-DD) and `today`, or null when `birth`
 * isn't a real calendar date. A 29 Feb birthday counts as reached on 1 Mar in
 * non-leap years (the month/day comparison below gives that for free).
 */
export function ageOn(birth: string, today: Date): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(birth);
  if (!m) return null;
  const year = Number(m[1]);
  const month = Number(m[2]);
  const day = Number(m[3]);
  const check = new Date(year, month - 1, day);
  if (check.getFullYear() !== year || check.getMonth() !== month - 1 || check.getDate() !== day) return null;

  let age = today.getFullYear() - year;
  const beforeBirthday =
    today.getMonth() + 1 < month || (today.getMonth() + 1 === month && today.getDate() < day);
  if (beforeBirthday) age -= 1;
  return age;
}

/** 'invalid' when unparseable, in the future, or more than 120 years ago. */
export function validateBirthDate(birth: string, today: Date): 'ok' | 'invalid' {
  const age = ageOn(birth, today);
  if (age === null || age < 0 || age > MAX_AGE) return 'invalid';
  return 'ok';
}
