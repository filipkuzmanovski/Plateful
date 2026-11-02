import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@/theme/ThemeProvider';
import { expectNoEmoji } from '@/test/noEmoji';
import ThemeMenu from './ThemeMenu';

function renderMenu() {
  return render(
    <ThemeProvider>
      <ThemeMenu />
    </ThemeProvider>,
  );
}

const button = () => screen.getByRole('button', { name: /^Theme:/ });

test('buttonStartsAsMatchMyDeviceAndClosed', () => {
  renderMenu();
  expect(button()).toHaveAccessibleName('Theme: Match my device');
  expect(button()).toHaveAttribute('aria-haspopup', 'menu');
  expect(button()).toHaveAttribute('aria-expanded', 'false');
  expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  expectNoEmoji();
});

test('opensWithThreeRadioItemsAndFocusOnTheChecked', async () => {
  renderMenu();
  await userEvent.click(button());

  expect(button()).toHaveAttribute('aria-expanded', 'true');
  expect(screen.getByRole('menu')).toBeInTheDocument();
  const items = screen.getAllByRole('menuitemradio');
  expect(items.map((i) => i.textContent)).toEqual(['Match my device', 'Light', 'Dark']);
  expect(screen.getByRole('menuitemradio', { name: 'Match my device' })).toHaveAttribute('aria-checked', 'true');
  expect(screen.getByRole('menuitemradio', { name: 'Light' })).toHaveAttribute('aria-checked', 'false');
  expect(screen.getByRole('menuitemradio', { name: 'Match my device' })).toHaveFocus();
  expectNoEmoji();
});

test('arrowDownThenEnterChoosesLightAndReturnsFocus', async () => {
  renderMenu();
  await userEvent.click(button());
  await userEvent.keyboard('{ArrowDown}');
  expect(screen.getByRole('menuitemradio', { name: 'Light' })).toHaveFocus();
  await userEvent.keyboard('{Enter}');

  expect(document.documentElement.dataset.theme).toBe('light');
  expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  expect(button()).toHaveFocus();
  expect(button()).toHaveAccessibleName('Theme: Light');
});

test('arrowKeysWrapAndHomeEndJump', async () => {
  renderMenu();
  await userEvent.click(button());
  await userEvent.keyboard('{ArrowUp}');
  expect(screen.getByRole('menuitemradio', { name: 'Dark' })).toHaveFocus();
  await userEvent.keyboard('{ArrowDown}');
  expect(screen.getByRole('menuitemradio', { name: 'Match my device' })).toHaveFocus();
  await userEvent.keyboard('{End}');
  expect(screen.getByRole('menuitemradio', { name: 'Dark' })).toHaveFocus();
  await userEvent.keyboard('{Home}');
  expect(screen.getByRole('menuitemradio', { name: 'Match my device' })).toHaveFocus();
});

test('clickingDarkChoosesItAndMarksItChecked', async () => {
  renderMenu();
  await userEvent.click(button());
  await userEvent.click(screen.getByRole('menuitemradio', { name: 'Dark' }));

  expect(document.documentElement.dataset.theme).toBe('dark');
  expect(button()).toHaveAccessibleName('Theme: Dark');
  await userEvent.click(button());
  expect(screen.getByRole('menuitemradio', { name: 'Dark' })).toHaveAttribute('aria-checked', 'true');
});

test('escapeClosesAndReturnsFocus', async () => {
  renderMenu();
  await userEvent.click(button());
  await userEvent.keyboard('{Escape}');

  expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  expect(button()).toHaveFocus();
  expect(button()).toHaveAttribute('aria-expanded', 'false');
});

test('outsideMouseDownCloses', async () => {
  renderMenu();
  await userEvent.click(button());
  fireEvent.mouseDown(document.body);
  expect(screen.queryByRole('menu')).not.toBeInTheDocument();
});
