import { ApiError } from '@/api/client';
import { aiErrorMessage, PHOTO_UNREADABLE } from './aiMessages';

const err = (status: number, code: string) => new ApiError(status, 'server detail', undefined, code);

describe('aiErrorMessage for the photo scan', () => {
  it('maps invalid_photo', () => {
    expect(aiErrorMessage(err(400, 'invalid_photo'))).toBe('Use 1 to 3 photos (JPEG, up to 1 MB each).');
  });
  it('maps scan_limit', () => {
    expect(aiErrorMessage(err(429, 'scan_limit'))).toBe("You've used today's 10 photo scans. More tomorrow.");
  });
  it('maps scan_failed', () => {
    expect(aiErrorMessage(err(422, 'scan_failed'))).toBe(
      "Couldn't read the photo. Try again or type your ingredients.",
    );
  });
  it('exposes the unreadable-photo copy', () => {
    expect(PHOTO_UNREADABLE).toBe("This photo type can't be opened here. Try a JPEG or take a new photo.");
  });
});
