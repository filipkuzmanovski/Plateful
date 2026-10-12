import { useState } from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expectNoEmoji } from '@/test/noEmoji';
import { PhotoPicker } from './PhotoPicker';
import { deletePhotos, uploadPhoto } from '@/api/photos';
import { prepareImage } from '@/lib/resizeImage';

vi.mock('@/api/photos', () => ({
  MAX_PHOTOS: 6,
  ACCEPTED_TYPES: ['image/jpeg', 'image/png', 'image/webp'],
  publicPhotoUrl: (p: string) => `https://img.test/${p}`,
  uploadPhoto: vi.fn(),
  deletePhotos: vi.fn(),
}));
vi.mock('@/lib/resizeImage', () => ({ prepareImage: vi.fn() }));

const uploadMock = vi.mocked(uploadPhoto);
const deleteMock = vi.mocked(deletePhotos);
const prepareMock = vi.mocked(prepareImage);

// jsdom has no createObjectURL; assign stubs so the component can create and revoke previews.
const createUrl = vi.fn(() => 'blob:preview');
const revokeUrl = vi.fn();

beforeEach(() => {
  uploadMock.mockReset();
  deleteMock.mockReset();
  prepareMock.mockReset();
  prepareMock.mockResolvedValue({ blob: new Blob(['x'], { type: 'image/webp' }), ext: 'webp' });
  createUrl.mockClear();
  revokeUrl.mockClear();
  URL.createObjectURL = createUrl;
  URL.revokeObjectURL = revokeUrl;
});

// applyAccept: false so the component's own type check is what gets exercised.
const setup = () => userEvent.setup({ applyAccept: false });
const jpeg = (name: string) => new File(['x'], name, { type: 'image/jpeg' });
const deferred = () => {
  let resolve!: (p: string) => void;
  const promise = new Promise<string>((r) => (resolve = r));
  return { promise, resolve };
};

function Harness({
  initial = [],
  onChange = () => {},
  onBusyChange = () => {},
}: {
  initial?: string[];
  onChange?: (p: string[]) => void;
  onBusyChange?: (b: boolean) => void;
}) {
  const [value, setValue] = useState(initial);
  return (
    <PhotoPicker
      value={value}
      userId="u"
      onBusyChange={onBusyChange}
      onChange={(p) => {
        setValue(p);
        onChange(p);
      }}
    />
  );
}

test('uploadsSelectedFilesInOrder', async () => {
  uploadMock.mockResolvedValueOnce('u/1.webp').mockResolvedValueOnce('u/2.webp');
  const onChange = vi.fn();
  render(<Harness onChange={onChange} />);
  await setup().upload(screen.getByLabelText('Add photos'), [jpeg('a.jpg'), jpeg('b.jpg')]);

  await waitFor(() => expect(onChange).toHaveBeenLastCalledWith(['u/1.webp', 'u/2.webp']));
  expect(uploadMock).toHaveBeenCalledTimes(2);
  expect(uploadMock).toHaveBeenCalledWith('u', expect.any(Blob), 'webp');
  expect(screen.getAllByRole('img')).toHaveLength(2);
  expectNoEmoji();
});

test('refusesFilesBeyondSix', async () => {
  uploadMock.mockResolvedValueOnce('u/5.webp').mockResolvedValueOnce('u/6.webp');
  const onChange = vi.fn();
  render(<Harness initial={['u/1.webp', 'u/2.webp', 'u/3.webp', 'u/4.webp']} onChange={onChange} />);
  await setup().upload(screen.getByLabelText('Add photos'), [jpeg('a.jpg'), jpeg('b.jpg'), jpeg('c.jpg')]);

  expect(await screen.findByRole('alert')).toHaveTextContent('You can add up to 6 photos.');
  await waitFor(() => expect(onChange).toHaveBeenCalledTimes(2));
  expect(uploadMock).toHaveBeenCalledTimes(2);
  // All six slots are used now.
  expect(screen.getByRole('button', { name: 'Add photos' })).toBeDisabled();
});

test('refusesWrongType', async () => {
  render(<Harness />);
  await setup().upload(screen.getByLabelText('Add photos'), new File(['x'], 'a.gif', { type: 'image/gif' }));

  expect(await screen.findByRole('alert')).toHaveTextContent('Only JPEG, PNG or WebP photos.');
  expect(prepareMock).not.toHaveBeenCalled();
  expect(uploadMock).not.toHaveBeenCalled();
});

test('mixedSelectionAcceptsValidFilesAndShowsTypeAlert', async () => {
  uploadMock.mockResolvedValueOnce('u/1.webp');
  const onChange = vi.fn();
  render(<Harness onChange={onChange} />);
  await setup().upload(screen.getByLabelText('Add photos'), [
    new File(['x'], 'a.gif', { type: 'image/gif' }),
    jpeg('b.jpg'),
  ]);

  expect(await screen.findByRole('alert')).toHaveTextContent('Only JPEG, PNG or WebP photos.');
  await waitFor(() => expect(onChange).toHaveBeenLastCalledWith(['u/1.webp']));
  expect(uploadMock).toHaveBeenCalledTimes(1);
});

test('removeAndMove', async () => {
  const onChange = vi.fn();
  render(<Harness initial={['u/a.webp', 'u/b.webp', 'u/c.webp']} onChange={onChange} />);
  const user = setup();

  expect(screen.getByRole('button', { name: 'Move photo 1 left' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Move photo 3 right' })).toBeDisabled();

  await user.click(screen.getByRole('button', { name: 'Move photo 3 left' }));
  expect(onChange).toHaveBeenLastCalledWith(['u/a.webp', 'u/c.webp', 'u/b.webp']);

  await user.click(screen.getByRole('button', { name: 'Remove photo 1' }));
  expect(onChange).toHaveBeenLastCalledWith(['u/c.webp', 'u/b.webp']);
});

test('coverPillOnFirst', () => {
  render(<Harness initial={['u/a.webp', 'u/b.webp']} />);
  const tiles = screen.getAllByRole('listitem');
  expect(within(tiles[0]).getByText('Cover')).toBeInTheDocument();
  expect(within(tiles[1]).queryByText('Cover')).not.toBeInTheDocument();
  expect(screen.getAllByText('Cover')).toHaveLength(1);
});

test('busyWhilePendingAndOnError', async () => {
  const first = deferred();
  uploadMock.mockReturnValueOnce(first.promise);
  const onBusyChange = vi.fn();
  render(<Harness onBusyChange={onBusyChange} />);
  const user = setup();

  await user.upload(screen.getByLabelText('Add photos'), jpeg('a.jpg'));
  await waitFor(() => expect(onBusyChange).toHaveBeenLastCalledWith(true));
  expect(screen.getByRole('status', { name: 'Uploading photo 1' })).toBeInTheDocument();

  first.resolve('u/1.webp');
  await waitFor(() => expect(onBusyChange).toHaveBeenLastCalledWith(false));
  expect(screen.queryByRole('status')).not.toBeInTheDocument();

  // A failed prepare leaves an error tile and keeps the form busy.
  prepareMock.mockRejectedValueOnce(new Error("Couldn't read this image"));
  await user.upload(screen.getByLabelText('Add photos'), jpeg('b.jpg'));
  expect(await screen.findByText("Couldn't read this image")).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  expect(onBusyChange).toHaveBeenLastCalledWith(true);

  await user.click(screen.getByRole('button', { name: 'Remove' }));
  await waitFor(() => expect(onBusyChange).toHaveBeenLastCalledWith(false));
  expect(screen.queryByText("Couldn't read this image")).not.toBeInTheDocument();
});

test('retryReRunsPrepareAndUploadOnTheSameFile', async () => {
  prepareMock.mockRejectedValueOnce(new Error("Couldn't read this image"));
  uploadMock.mockResolvedValueOnce('u/1.webp');
  const onChange = vi.fn();
  render(<Harness onChange={onChange} />);
  const user = setup();
  const file = jpeg('a.jpg');

  await user.upload(screen.getByLabelText('Add photos'), file);
  await user.click(await screen.findByRole('button', { name: 'Retry' }));

  await waitFor(() => expect(onChange).toHaveBeenLastCalledWith(['u/1.webp']));
  expect(prepareMock).toHaveBeenCalledTimes(2);
  expect(prepareMock).toHaveBeenNthCalledWith(2, file);
});

test('removingAPendingTileIgnoresItsLateUpload', async () => {
  const late = deferred();
  uploadMock.mockReturnValueOnce(late.promise);
  const onChange = vi.fn();
  const onBusyChange = vi.fn();
  render(<Harness onChange={onChange} onBusyChange={onBusyChange} />);
  const user = setup();

  await user.upload(screen.getByLabelText('Add photos'), jpeg('a.jpg'));
  await user.click(await screen.findByRole('button', { name: 'Remove photo 1' }));
  await waitFor(() => expect(onBusyChange).toHaveBeenLastCalledWith(false));

  late.resolve('u/late.webp');
  await new Promise((r) => setTimeout(r, 0));
  expect(onChange).not.toHaveBeenCalled();
  expect(screen.queryAllByRole('listitem')).toHaveLength(0);
});

test('doesNotUploadQueuedTilesRemovedBeforeTheirTurn', async () => {
  const first = deferred();
  uploadMock.mockReturnValueOnce(first.promise).mockResolvedValueOnce('u/2.webp');
  const onChange = vi.fn();
  render(<Harness onChange={onChange} />);
  const user = setup();

  await user.upload(screen.getByLabelText('Add photos'), [jpeg('a.jpg'), jpeg('b.jpg'), jpeg('c.jpg')]);
  await user.click(await screen.findByRole('button', { name: 'Remove photo 3' }));

  first.resolve('u/1.webp');
  await waitFor(() => expect(onChange).toHaveBeenLastCalledWith(['u/1.webp', 'u/2.webp']));
  expect(uploadMock).toHaveBeenCalledTimes(2);
  expect(prepareMock).toHaveBeenCalledTimes(2);
  expect(deleteMock).not.toHaveBeenCalled();
});

test('deletesTheFileOfATileRemovedMidUpload', async () => {
  const late = deferred();
  uploadMock.mockReturnValueOnce(late.promise);
  const onChange = vi.fn();
  render(<Harness onChange={onChange} />);
  const user = setup();

  await user.upload(screen.getByLabelText('Add photos'), jpeg('a.jpg'));
  await user.click(await screen.findByRole('button', { name: 'Remove photo 1' }));

  late.resolve('u/late.webp');
  await waitFor(() => expect(deleteMock).toHaveBeenCalledWith(['u/late.webp']));
  expect(onChange).not.toHaveBeenCalled();
});

test('stopsUploadingQueuedFilesAfterUnmount', async () => {
  const first = deferred();
  uploadMock.mockReturnValueOnce(first.promise);
  const { unmount } = render(<Harness />);

  await setup().upload(screen.getByLabelText('Add photos'), [jpeg('a.jpg'), jpeg('b.jpg')]);
  unmount();

  first.resolve('u/1.webp');
  await waitFor(() => expect(deleteMock).toHaveBeenCalledWith(['u/1.webp']));
  expect(uploadMock).toHaveBeenCalledTimes(1);
});

test('revokesPreviewUrlsWhenDoneRemovedOrUnmounted', async () => {
  const pending = deferred();
  const stuck = deferred();
  uploadMock
    .mockResolvedValueOnce('u/1.webp')
    .mockReturnValueOnce(pending.promise)
    .mockReturnValueOnce(stuck.promise);
  const { unmount } = render(<Harness />);
  const user = setup();

  await user.upload(screen.getByLabelText('Add photos'), jpeg('a.jpg'));
  await waitFor(() => expect(revokeUrl).toHaveBeenCalledTimes(1)); // finished

  await user.upload(screen.getByLabelText('Add photos'), jpeg('b.jpg'));
  await user.click(await screen.findByRole('button', { name: 'Remove photo 2' }));
  expect(revokeUrl).toHaveBeenCalledTimes(2); // removed while pending

  await user.upload(screen.getByLabelText('Add photos'), jpeg('c.jpg'));
  expect(createUrl).toHaveBeenCalledTimes(3);
  unmount();
  expect(revokeUrl).toHaveBeenCalledTimes(3); // unmounted while pending
});

test('addButtonDisabledWhenFull', () => {
  render(<Harness initial={['1', '2', '3', '4', '5', '6'].map((n) => `u/${n}.webp`)} />);
  expect(screen.getByRole('button', { name: 'Add photos' })).toBeDisabled();
});
