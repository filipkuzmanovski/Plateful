import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { toast } from 'sonner';
import { ShareRecipe } from './ShareRecipe';
import { recipeFixture } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import { buildShareLinks } from '@/lib/shareLinks';

vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

const recipe = recipeFixture({ id: 'r-7', title: 'Lemon Tart & Cream', image: 'https://img.test/tart.jpg' });
const links = buildShareLinks({ origin: window.location.origin, id: recipe.id, title: recipe.title, image: recipe.image });

function setNav(props: Record<string, unknown>) {
  for (const [k, v] of Object.entries(props)) {
    Object.defineProperty(navigator, k, { value: v, configurable: true, writable: true });
  }
}

afterEach(() => {
  for (const k of ['share', 'canShare', 'clipboard']) Reflect.deleteProperty(navigator, k);
  vi.clearAllMocks();
});

describe('native share', () => {
  test('calls navigator.share with title, text and url and opens no dialog', async () => {
    const share = vi.fn().mockResolvedValue(undefined);
    const canShare = vi.fn().mockReturnValue(true);
    setNav({ share, canShare });
    render(<ShareRecipe recipe={recipe} />);
    await userEvent.click(screen.getByRole('button', { name: 'Share' }));
    expect(canShare).toHaveBeenCalledWith({ title: links.title, text: links.text, url: links.url });
    expect(share).toHaveBeenCalledWith({ title: links.title, text: links.text, url: links.url });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  test('canShare returning false falls through to the menu', async () => {
    const share = vi.fn();
    setNav({ share, canShare: vi.fn().mockReturnValue(false) });
    render(<ShareRecipe recipe={recipe} />);
    await userEvent.click(screen.getByRole('button', { name: 'Share' }));
    expect(share).not.toHaveBeenCalled();
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });

  test('share without canShare is still used', async () => {
    const share = vi.fn().mockResolvedValue(undefined);
    setNav({ share });
    render(<ShareRecipe recipe={recipe} />);
    await userEvent.click(screen.getByRole('button', { name: 'Share' }));
    expect(share).toHaveBeenCalledOnce();
  });

  test('an AbortError is ignored: no dialog, no toast', async () => {
    const share = vi.fn().mockRejectedValue(new DOMException('cancelled', 'AbortError'));
    setNav({ share });
    render(<ShareRecipe recipe={recipe} />);
    await userEvent.click(screen.getByRole('button', { name: 'Share' }));
    expect(share).toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(toast.error).not.toHaveBeenCalled();
    expect(toast.success).not.toHaveBeenCalled();
  });

  test('any other error opens the menu', async () => {
    setNav({ share: vi.fn().mockRejectedValue(new Error('boom')) });
    render(<ShareRecipe recipe={recipe} />);
    await userEvent.click(screen.getByRole('button', { name: 'Share' }));
    expect(await screen.findByRole('dialog', { name: 'Share this recipe' })).toBeInTheDocument();
  });
});

describe('share menu', () => {
  test('lists all five links with exact hrefs and a read-only link field', async () => {
    render(<ShareRecipe recipe={recipe} />);
    await userEvent.click(screen.getByRole('button', { name: 'Share' }));
    const dialog = await screen.findByRole('dialog', { name: 'Share this recipe' });
    const d = within(dialog);

    expect(d.getByRole('textbox')).toHaveValue(links.url);
    expect(d.getByRole('textbox')).toHaveAttribute('readonly');
    expect(d.getByRole('button', { name: 'Copy link' })).toBeInTheDocument();

    const expected: [string, string][] = [
      ['Facebook', links.facebook], ['X', links.x], ['WhatsApp', links.whatsapp],
      ['Pinterest', links.pinterest], ['Email', links.email],
    ];
    for (const [name, href] of expected) {
      const a = d.getByRole('link', { name });
      expect(a).toHaveAttribute('href', href);
      if (name === 'Email') {
        expect(a).not.toHaveAttribute('target');
      } else {
        expect(a).toHaveAttribute('target', '_blank');
        expect(a).toHaveAttribute('rel', 'noopener noreferrer');
      }
    }
    expectNoEmoji();
  });

  test('Copy link writes the url and toasts', async () => {
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    render(<ShareRecipe recipe={recipe} />);
    await user.click(screen.getByRole('button', { name: 'Share' }));
    // user-event installs its own clipboard stub on setup; replace it before the click.
    setNav({ clipboard: { writeText } });
    await user.click(await screen.findByRole('button', { name: 'Copy link' }));
    expect(writeText).toHaveBeenCalledWith(links.url);
    expect(toast.success).toHaveBeenCalledWith('Link copied');
  });

  test('a rejected clipboard toasts an error and keeps the field', async () => {
    const user = userEvent.setup();
    render(<ShareRecipe recipe={recipe} />);
    await user.click(screen.getByRole('button', { name: 'Share' }));
    setNav({ clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } });
    await user.click(await screen.findByRole('button', { name: 'Copy link' }));
    expect(toast.error).toHaveBeenCalledWith("Couldn't copy the link.");
    expect(toast.success).not.toHaveBeenCalled();
    expect(screen.getByRole('textbox')).toHaveValue(links.url);
  });

  test('Pinterest link has no media when the recipe has no image', async () => {
    render(<ShareRecipe recipe={{ ...recipe, image: null }} />);
    await userEvent.click(screen.getByRole('button', { name: 'Share' }));
    const href = (await screen.findByRole('link', { name: 'Pinterest' })).getAttribute('href')!;
    expect(href).not.toContain('media=');
  });
});
