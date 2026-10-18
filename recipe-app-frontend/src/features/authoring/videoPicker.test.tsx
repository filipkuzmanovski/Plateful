import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { recipeFixture, profileFixture, pageOf } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import { deleteVideoFiles, publicVideoUrl, uploadPoster, uploadVideo } from '@/api/videos';
import { capturePoster, probeVideo } from '@/lib/videoProbe';

const BASE = 'http://localhost:8080';
const PENDING = {
  status: 'pending' as const, rejectionReason: null, everApproved: false, revision: 1,
  submittedAt: '2026-10-18T10:00:00Z',
};

const TYPE_MESSAGE = 'Videos must be MP4, WebM or MOV, up to 50 MB.';
const UNPLAYABLE_MESSAGE = "This video can't be played in most browsers. Please export it as MP4 and try again.";
const TOO_LONG_MESSAGE = 'Videos can be up to 60 seconds long.';
const UPLOAD_FAILED_MESSAGE = "Couldn't upload the video. Please try again.";

// Storage and the browser's media decoder are never reached; the URL helpers stay real.
vi.mock('@/api/videos', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/videos')>()),
  uploadVideo: vi.fn(),
  uploadPoster: vi.fn(),
  deleteVideoFiles: vi.fn(async () => {}),
}));
vi.mock('@/lib/videoProbe', () => ({ probeVideo: vi.fn(), capturePoster: vi.fn() }));

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({
        data: { session: { access_token: 'test-token', user: { id: 'u-1', email: 'jane@example.com' } } },
      }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
      signOut: async () => {},
    },
  },
}));

const uploadVideoMock = vi.mocked(uploadVideo);
const uploadPosterMock = vi.mocked(uploadPoster);
const deleteMock = vi.mocked(deleteVideoFiles);
const probeMock = vi.mocked(probeVideo);
const posterMock = vi.mocked(capturePoster);

beforeEach(() => {
  for (const m of [uploadVideoMock, uploadPosterMock, deleteMock, probeMock, posterMock]) m.mockReset();
  deleteMock.mockResolvedValue(undefined);
  probeMock.mockResolvedValue({ playable: true, durationSeconds: 41.2 });
  posterMock.mockResolvedValue(new Blob(['p'], { type: 'image/jpeg' }));
  uploadVideoMock.mockResolvedValue('u-1/clip.mp4');
  uploadPosterMock.mockResolvedValue('u-1/clip.jpg');
  // jsdom has no object URLs.
  URL.createObjectURL = vi.fn(() => 'blob:clip');
  URL.revokeObjectURL = vi.fn();
  server.use(http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json([])));
});

// applyAccept: false so the component's own type check is what gets exercised.
const user = () => userEvent.setup({ applyAccept: false });
const clip = (name = 'a.mp4', type = 'video/mp4') => new File(['x'], name, { type });
const pick = (file: File) => user().upload(screen.getByLabelText('Add video'), file);
const saveButton = () => screen.getByRole('button', { name: /^save recipe$/i });
const poster = () => screen.getByRole('img', { name: 'Video poster' });

function stubMe() {
  server.use(http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)));
}

async function fillRequired() {
  await userEvent.type(await screen.findByLabelText(/title/i), 'X');
  await userEvent.type(screen.getByLabelText(/ingredient 1 name/i), 'a');
  await userEvent.type(screen.getByLabelText(/ingredient 1 text/i), 'b');
}

function stubCreate() {
  const state = { posted: null as any };
  const created = recipeFixture({ id: 'r-new', title: 'X', user: profileFixture, review: PENDING });
  server.use(
    http.post(`${BASE}/api/recipes`, async ({ request }) => {
      state.posted = await request.json();
      return HttpResponse.json(created, { status: 201 });
    }),
    http.get(`${BASE}/api/recipes/r-new`, () => HttpResponse.json(created)),
    http.get(`${BASE}/api/recipes/r-new/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/r-new/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
  );
  return state;
}

const EXISTING = {
  url: publicVideoUrl('u-1/old.mp4'),
  posterUrl: publicVideoUrl('u-1/old.jpg'),
  durationSeconds: 42,
};

function stubEditWithVideo() {
  stubMe();
  let current = recipeFixture({ user: profileFixture, video: EXISTING });
  const state = { putBody: null as any, log: [] as string[] };
  server.use(
    http.get(`${BASE}/api/recipes/r-1`, () => HttpResponse.json(current)),
    http.put(`${BASE}/api/recipes/r-1`, async ({ request }) => {
      state.putBody = await request.json();
      state.log.push('put');
      current = { ...current, video: null, review: PENDING };
      return HttpResponse.json(current);
    }),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
  );
  deleteMock.mockImplementation(async () => { state.log.push('delete'); });
  return state;
}

test('fieldIsLabelledAndOptional', async () => {
  stubMe();
  renderApp('/recipes/new');
  await screen.findByLabelText(/title/i);
  expect(screen.getByText('Video (optional)')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Add video' })).toBeEnabled();
  expect(screen.getByLabelText('Add video')).toHaveAttribute('accept', 'video/mp4,video/webm,video/quicktime');
});

test('rejectsWrongTypeOrSize', async () => {
  stubMe();
  renderApp('/recipes/new');
  await screen.findByLabelText(/title/i);

  await pick(clip('a.mkv', 'video/x-matroska'));
  expect(await screen.findByText(TYPE_MESSAGE)).toBeInTheDocument();

  const big = clip('big.mp4');
  Object.defineProperty(big, 'size', { value: 52_428_801 });
  await pick(big);
  expect(screen.getByText(TYPE_MESSAGE)).toBeInTheDocument();

  expect(probeMock).not.toHaveBeenCalled();
  expect(uploadVideoMock).not.toHaveBeenCalled();
  expectNoEmoji();
});

test('acceptsAFileExactlyAtTheSizeLimit', async () => {
  stubMe();
  renderApp('/recipes/new');
  await screen.findByLabelText(/title/i);
  const edge = clip('edge.mp4');
  Object.defineProperty(edge, 'size', { value: 52_428_800 });
  await pick(edge);
  expect(await screen.findByRole('button', { name: 'Play' })).toBeInTheDocument();
  expect(screen.queryByText(TYPE_MESSAGE)).not.toBeInTheDocument();
});

test('rejectsUnplayable', async () => {
  stubMe();
  probeMock.mockResolvedValue({ playable: false });
  renderApp('/recipes/new');
  await screen.findByLabelText(/title/i);
  await pick(clip());
  expect(await screen.findByText(UNPLAYABLE_MESSAGE)).toBeInTheDocument();
  expect(uploadVideoMock).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: 'Add video' })).toBeEnabled();
});

test('rejectsTooLong: the stored duration is the rounded-up probe duration and over 60 is rejected', async () => {
  stubMe();
  renderApp('/recipes/new');
  await screen.findByLabelText(/title/i);

  probeMock.mockResolvedValue({ playable: true, durationSeconds: 60.4 });
  await pick(clip());
  expect(await screen.findByText(TOO_LONG_MESSAGE)).toBeInTheDocument();
  expect(uploadVideoMock).not.toHaveBeenCalled();

  probeMock.mockResolvedValue({ playable: true, durationSeconds: 60.0 });
  await pick(clip());
  expect(await screen.findByText('1:00')).toBeInTheDocument(); // accepted as 60
  expect(screen.queryByText(TOO_LONG_MESSAGE)).not.toBeInTheDocument();

  probeMock.mockResolvedValue({ playable: true, durationSeconds: 0.4 });
  await pick(clip());
  expect(await screen.findByText('0:01')).toBeInTheDocument(); // stored as 1
});

test('pickerDisabledWhileUploading: the field says so and Save is disabled until it finishes', async () => {
  stubMe();
  let finish!: (p: string) => void;
  uploadVideoMock.mockReturnValue(new Promise<string>((r) => (finish = r)));
  renderApp('/recipes/new');
  await screen.findByLabelText(/title/i);
  expect(saveButton()).toBeEnabled();

  await pick(clip());
  expect(await screen.findByText('Uploading video…')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Add video' })).toBeDisabled();
  expect(saveButton()).toBeDisabled();

  finish('u-1/clip.mp4');
  await waitFor(() => expect(saveButton()).toBeEnabled());
  expect(screen.queryByText('Uploading video…')).not.toBeInTheDocument();
  expectNoEmoji();
});

test('successShowsPosterDurationAndActions', async () => {
  stubMe();
  renderApp('/recipes/new');
  await screen.findByLabelText(/title/i);
  await pick(clip());

  expect(await screen.findByText('0:42')).toBeInTheDocument();
  expect(poster()).toHaveAttribute('src', publicVideoUrl('u-1/clip.jpg'));
  expect(screen.getByRole('button', { name: 'Play' })).toBeEnabled();
  expect(screen.getByRole('button', { name: 'Replace' })).toBeEnabled();
  expect(screen.getByRole('button', { name: 'Remove' })).toBeEnabled();
  expect(screen.queryByRole('button', { name: 'Add video' })).not.toBeInTheDocument();
  expect(uploadVideoMock).toHaveBeenCalledWith('u-1', expect.any(File));
  expect(uploadPosterMock).toHaveBeenCalledWith('u-1', expect.any(Blob));
  expect(posterMock).toHaveBeenCalledWith(expect.any(File), 1);
  expectNoEmoji();
});

test('playOpensAPreviewOfTheClip', async () => {
  stubMe();
  renderApp('/recipes/new');
  await screen.findByLabelText(/title/i);
  await pick(clip());
  await userEvent.click(await screen.findByRole('button', { name: 'Play' }));

  const dialog = await screen.findByRole('dialog');
  expect(dialog.querySelector('video')).toHaveAttribute('src', 'blob:clip');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Close' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
});

test('uploadFailureShowsMessage: the orphaned half of the pair is deleted', async () => {
  stubMe();
  uploadPosterMock.mockRejectedValue(new Error('boom'));
  renderApp('/recipes/new');
  await screen.findByLabelText(/title/i);
  await pick(clip());

  expect(await screen.findByText(UPLOAD_FAILED_MESSAGE)).toBeInTheDocument();
  expect(deleteMock).toHaveBeenCalledWith(['u-1/clip.mp4']);
  expect(screen.getByRole('button', { name: 'Add video' })).toBeEnabled();
  expect(saveButton()).toBeEnabled();
});

test('uploadFailureOfTheClipDeletesThePoster', async () => {
  stubMe();
  uploadVideoMock.mockRejectedValue(new Error('boom'));
  renderApp('/recipes/new');
  await screen.findByLabelText(/title/i);
  await pick(clip());

  expect(await screen.findByText(UPLOAD_FAILED_MESSAGE)).toBeInTheDocument();
  expect(deleteMock).toHaveBeenCalledWith(['u-1/clip.jpg']);
});

test('createSendsVideo: the POST carries the paths and the rounded-up duration', async () => {
  stubMe();
  const state = stubCreate();
  renderApp('/recipes/new');
  await fillRequired();
  await pick(clip());
  await screen.findByText('0:42');
  await userEvent.click(saveButton());

  expect(await screen.findByRole('heading', { name: 'X' })).toBeInTheDocument();
  expect(state.posted.video).toEqual({ path: 'u-1/clip.mp4', posterPath: 'u-1/clip.jpg', durationSeconds: 42 });
  expect(deleteMock).not.toHaveBeenCalled();
});

test('createWithoutVideoSendsNull', async () => {
  stubMe();
  const state = stubCreate();
  renderApp('/recipes/new');
  await fillRequired();
  await userEvent.click(saveButton());
  expect(await screen.findByRole('heading', { name: 'X' })).toBeInTheDocument();
  expect(state.posted.video).toBeNull();
});

test('editPrefillsTheExistingVideo', async () => {
  stubEditWithVideo();
  renderApp('/recipes/r-1/edit');
  expect(await screen.findByText('0:42')).toBeInTheDocument();
  expect(poster()).toHaveAttribute('src', EXISTING.posterUrl);
  expect(screen.getByRole('button', { name: 'Replace' })).toBeInTheDocument();
});

test('removeThenSaveSendsNullAndDeletesAfterSave', async () => {
  const state = stubEditWithVideo();
  renderApp('/recipes/r-1/edit');
  await userEvent.click(await screen.findByRole('button', { name: 'Remove' }));
  expect(screen.getByRole('button', { name: 'Add video' })).toBeInTheDocument();
  expect(deleteMock).not.toHaveBeenCalled(); // nothing is deleted before saving

  await userEvent.click(saveButton());
  await waitFor(() => expect(deleteMock).toHaveBeenCalled());
  expect(state.putBody.video).toBeNull();
  expect(deleteMock.mock.calls[0][0].sort()).toEqual(['u-1/old.jpg', 'u-1/old.mp4']);
  expect(state.log).toEqual(['put', 'delete']);
});

test('replaceThenSaveDeletesTheOriginalAndKeepsTheNewFiles', async () => {
  const state = stubEditWithVideo();
  renderApp('/recipes/r-1/edit');
  await screen.findByText('0:42');
  await pick(clip('new.mp4'));
  await waitFor(() => expect(poster()).toHaveAttribute('src', publicVideoUrl('u-1/clip.jpg')));
  expect(deleteMock).not.toHaveBeenCalled();

  await userEvent.click(saveButton());
  await waitFor(() => expect(deleteMock).toHaveBeenCalled());
  expect(state.putBody.video).toEqual({ path: 'u-1/clip.mp4', posterPath: 'u-1/clip.jpg', durationSeconds: 42 });
  expect(deleteMock.mock.calls[0][0].sort()).toEqual(['u-1/old.jpg', 'u-1/old.mp4']);
});

test('replacedTwiceDeletesTheUnusedUploadAfterSave', async () => {
  const state = stubEditWithVideo();
  uploadVideoMock.mockResolvedValueOnce('u-1/first.mp4').mockResolvedValueOnce('u-1/second.mp4');
  uploadPosterMock.mockResolvedValueOnce('u-1/first.jpg').mockResolvedValueOnce('u-1/second.jpg');
  renderApp('/recipes/r-1/edit');
  await screen.findByText('0:42');
  await pick(clip('one.mp4'));
  await waitFor(() => expect(poster()).toHaveAttribute('src', publicVideoUrl('u-1/first.jpg')));
  await pick(clip('two.mp4'));
  await waitFor(() => expect(poster()).toHaveAttribute('src', publicVideoUrl('u-1/second.jpg')));
  expect(deleteMock).not.toHaveBeenCalled();

  await userEvent.click(saveButton());
  await waitFor(() => expect(deleteMock).toHaveBeenCalled());
  expect(state.putBody.video.path).toBe('u-1/second.mp4');
  expect(deleteMock.mock.calls[0][0].sort()).toEqual([
    'u-1/first.jpg', 'u-1/first.mp4', 'u-1/old.jpg', 'u-1/old.mp4',
  ]);
});

test('keepingTheVideoOnEditDeletesNothing', async () => {
  const state = stubEditWithVideo();
  renderApp('/recipes/r-1/edit');
  await screen.findByText('0:42');
  await userEvent.click(saveButton());
  await waitFor(() => expect(state.log).toContain('put'));
  expect(state.putBody.video).toEqual({ path: 'u-1/old.mp4', posterPath: 'u-1/old.jpg', durationSeconds: 42 });
  expect(deleteMock).not.toHaveBeenCalled();
});

test('editDropsAVideoWhoseUrlsAreNotInTheBucket', async () => {
  stubMe();
  const state = { putBody: null as any };
  const external = recipeFixture({
    user: profileFixture,
    video: { url: 'https://cdn.example.com/a.mp4', posterUrl: 'https://cdn.example.com/a.jpg', durationSeconds: 10 },
  });
  server.use(
    http.get(`${BASE}/api/recipes/r-1`, () => HttpResponse.json(external)),
    http.put(`${BASE}/api/recipes/r-1`, async ({ request }) => {
      state.putBody = await request.json();
      return HttpResponse.json(external);
    }),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
  );
  renderApp('/recipes/r-1/edit');
  await screen.findByRole('button', { name: 'Add video' });
  await userEvent.click(saveButton());
  await waitFor(() => expect(state.putBody).not.toBeNull());
  expect(state.putBody.video).toBeNull();
  expect(deleteMock).not.toHaveBeenCalled();
});

test('cancelEditDeletesNothing: replacing the video then leaving the form never touches the original files', async () => {
  stubEditWithVideo();
  renderApp('/recipes/r-1/edit');
  await screen.findByText('0:42');
  await pick(clip('new.mp4'));
  await waitFor(() => expect(poster()).toHaveAttribute('src', publicVideoUrl('u-1/clip.jpg')));

  server.use(http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))));
  await userEvent.click(screen.getAllByRole('link', { name: 'Browse' })[0]);
  await waitFor(() => expect(screen.queryByLabelText(/title/i)).not.toBeInTheDocument());

  expect(deleteMock).not.toHaveBeenCalled();
});
