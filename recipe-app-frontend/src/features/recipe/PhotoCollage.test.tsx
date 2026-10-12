import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expectNoEmoji } from '@/test/noEmoji';
import { PhotoCollage } from './PhotoCollage';

const urls = (n: number) => Array.from({ length: n }, (_, i) => `https://cdn.example.com/p${i + 1}.webp`);

function renderCollage(n: number) {
  render(<PhotoCollage photos={urls(n)} title="Pasta" />);
  return within(screen.getByTestId('collage-desktop'));
}

test.each([
  [1, 1],
  [2, 2],
  [3, 3],
  [6, 3],
])('with %i photos the desktop layout shows %i tiles', (photos, tiles) => {
  const desktop = renderCollage(photos);
  expect(desktop.getAllByRole('button')).toHaveLength(tiles);
});

test('with more than three photos the third tile says how many are hidden', () => {
  const desktop = renderCollage(6);
  const third = desktop.getByRole('button', { name: 'Open photo 3 of 6' });
  expect(within(third).getByText('+3')).toBeInTheDocument();
  expect(desktop.queryByText('+2')).not.toBeInTheDocument();
});

test('three photos have no overlay', () => {
  const desktop = renderCollage(3);
  expect(desktop.queryByText(/^\+\d/)).not.toBeInTheDocument();
});

test('images carry descriptive alt text', () => {
  const desktop = renderCollage(3);
  expect(desktop.getByAltText('Pasta, photo 2 of 3')).toBeInTheDocument();
});

test('no photos shows the placeholder instead of tiles', () => {
  render(<PhotoCollage photos={[]} title="Pasta" />);
  expect(screen.queryByRole('button')).not.toBeInTheDocument();
  expect(screen.queryByRole('img')).not.toBeInTheDocument();
});

test('the viewer opens on the clicked photo', async () => {
  const user = userEvent.setup();
  const desktop = renderCollage(3);
  await user.click(desktop.getByRole('button', { name: 'Open photo 2 of 3' }));
  const dialog = await screen.findByRole('dialog', { name: 'Pasta photos' });
  expect(within(dialog).getByText('2 / 3')).toBeInTheDocument();
  expect(within(dialog).getByAltText('Pasta, photo 2 of 3')).toBeInTheDocument();
  expectNoEmoji();
});

test('the viewer navigates with buttons and arrow keys, and Escape closes it', async () => {
  const user = userEvent.setup();
  const desktop = renderCollage(3);
  const tile = desktop.getByRole('button', { name: 'Open photo 2 of 3' });
  await user.click(tile);
  const dialog = await screen.findByRole('dialog');

  await user.click(within(dialog).getByRole('button', { name: 'Next photo' }));
  expect(within(dialog).getByText('3 / 3')).toBeInTheDocument();

  await user.keyboard('{ArrowLeft}');
  expect(within(dialog).getByText('2 / 3')).toBeInTheDocument();

  await user.keyboard('{ArrowRight}{ArrowRight}');
  expect(within(dialog).getByText('1 / 3')).toBeInTheDocument(); // wraps past the last photo

  await user.keyboard('{Escape}');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(tile).toHaveFocus(); // focus goes back to the tile that opened it
});

test('the Close button closes the viewer', async () => {
  const user = userEvent.setup();
  const desktop = renderCollage(2);
  await user.click(desktop.getByRole('button', { name: 'Open photo 1 of 2' }));
  await user.click(await screen.findByRole('button', { name: 'Close' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});

test('a single photo has no arrows and no counter', async () => {
  const user = userEvent.setup();
  const desktop = renderCollage(1);
  await user.click(desktop.getByRole('button', { name: 'Open photo 1 of 1' }));
  const dialog = await screen.findByRole('dialog');
  expect(within(dialog).queryByRole('button', { name: 'Next photo' })).not.toBeInTheDocument();
  expect(within(dialog).queryByRole('button', { name: 'Previous photo' })).not.toBeInTheDocument();
  expect(within(dialog).queryByText(/\d \/ \d/)).not.toBeInTheDocument();
  await user.keyboard('{ArrowRight}'); // inert with one photo
  expect(within(dialog).getByAltText('Pasta, photo 1 of 1')).toBeInTheDocument();
});

test('the phone layout opens the same viewer', async () => {
  const user = userEvent.setup();
  render(<PhotoCollage photos={urls(4)} title="Pasta" />);
  const phone = within(screen.getByTestId('collage-phone'));
  expect(phone.getAllByRole('button')).toHaveLength(4); // cover plus three thumbnails
  await user.click(phone.getByRole('button', { name: 'Open photo 4 of 4' }));
  expect(within(await screen.findByRole('dialog')).getByText('4 / 4')).toBeInTheDocument();
});
