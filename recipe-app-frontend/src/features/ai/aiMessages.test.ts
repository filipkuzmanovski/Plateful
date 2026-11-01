import { ApiError } from '@/api/client';
import { aiErrorMessage, PHOTO_UNREADABLE, SCREENSHOT_INVALID } from './aiMessages';

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

describe('aiErrorMessage for post imports', () => {
  it('maps no_dish', () => {
    expect(aiErrorMessage(err(422, 'no_dish'))).toBe(
      "Couldn't find a dish in this post. Try a screenshot that shows the food or the recipe.",
    );
  });
  it('maps invalid_post', () => {
    expect(aiErrorMessage(err(400, 'invalid_post'))).toBe(
      'Add a caption (up to 2,200 characters) or 1 to 2 screenshots.',
    );
  });
  it('exposes the screenshot-invalid copy', () => {
    expect(SCREENSHOT_INVALID).toBe('Use 1 or 2 screenshots (JPEG, up to 1 MB each).');
  });
});
