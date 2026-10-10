import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Pagination, pageWindow } from './Pagination';

test('pageWindow keeps first, last and current±2 with gaps', () => {
  expect(pageWindow(0, 34)).toEqual([0, 1, 2, 'gap', 33]);
  expect(pageWindow(15, 34)).toEqual([0, 'gap', 13, 14, 15, 16, 17, 'gap', 33]);
  expect(pageWindow(1, 3)).toEqual([0, 1, 2]);
});

test('renders a bounded set of buttons and Next moves forward', async () => {
  const onPage = vi.fn();
  render(<Pagination page={0} totalPages={34} onPage={onPage} />);
  expect(screen.getByRole('button', { name: 'Go to page 34' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Go to page 20' })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled();
  await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
  expect(onPage).toHaveBeenCalledWith(1);
});
