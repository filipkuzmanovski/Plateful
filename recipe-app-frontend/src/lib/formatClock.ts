/** Whole seconds as m:ss, e.g. 42 -> "0:42", 60 -> "1:00". */
export const formatClock = (totalSeconds: number): string =>
  `${Math.floor(totalSeconds / 60)}:${String(totalSeconds % 60).padStart(2, '0')}`;
