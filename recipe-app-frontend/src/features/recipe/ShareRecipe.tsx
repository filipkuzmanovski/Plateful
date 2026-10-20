import { useRef, useState, type MouseEvent } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { toast } from 'sonner';
import { Copy, Mail, Share2 } from 'lucide-react';
import { Button } from '@/components/ui';
import { buildShareLinks } from '@/lib/shareLinks';
import type { RecipeDisplay } from '@/lib/types';

function NewTab() {
  return <span className="sr-only">(opens in a new tab)</span>;
}

const linkClass =
  'inline-flex items-center justify-center gap-2 rounded-full border border-olive/20 bg-paper px-4 py-2 text-sm font-semibold text-olive transition hover:border-olive/50 hover:bg-cream focus-visible:ring-4 focus-visible:ring-saffron/30 focus-visible:outline-none';

/** "Share" on the recipe page: the native share sheet when the browser has one, otherwise a small menu of links. */
export function ShareRecipe({ recipe }: { recipe: Pick<RecipeDisplay, 'id' | 'title' | 'image'> }) {
  const [open, setOpen] = useState(false);
  const links = buildShareLinks({
    origin: window.location.origin,
    id: recipe.id,
    title: recipe.title,
    image: recipe.image,
  });

  // Focus goes back to the Share button on close; there is no Dialog.Trigger to do it.
  const returnFocus = useRef<HTMLElement | null>(null);
  // True while the native sheet is open, so a second click can't pop the menu behind it.
  const inFlight = useRef(false);
  const data = { title: links.title, text: links.text, url: links.url };

  async function share(e: MouseEvent<HTMLButtonElement>) {
    returnFocus.current = e.currentTarget;
    if (inFlight.current) return;
    // The native sheet is for touch-primary devices; desktop browsers get the menu.
    const touchPrimary = window.matchMedia?.('(pointer: coarse)').matches === true;
    if (touchPrimary && typeof navigator.share === 'function' && navigator.canShare?.(data) !== false) {
      inFlight.current = true;
      try {
        await navigator.share(data);
        return;
      } catch (err) {
        if (err instanceof DOMException && err.name === 'AbortError') return;
      } finally {
        inFlight.current = false;
      }
    }
    setOpen(true);
  }

  async function shareNatively() {
    try {
      await navigator.share(data);
      setOpen(false);
    } catch {
      // Cancelled or unsupported: the menu stays open with the other options.
    }
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(links.url);
      toast.success('Link copied');
    } catch {
      toast.error("Couldn't copy the link.");
    }
  }

  return (
    <>
      <Button type="button" variant="outline" onClick={share} className="!px-4 !py-2">
        <Share2 aria-hidden="true" className="size-3.5" />
        Share
      </Button>
      <Dialog.Root open={open} onOpenChange={setOpen}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-50 animate-overlay-in bg-olive/35 backdrop-blur-[2px]" />
          <Dialog.Content
            onCloseAutoFocus={(e) => {
              e.preventDefault();
              returnFocus.current?.focus();
            }}
            className="fixed top-1/2 left-1/2 z-50 w-[min(26rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 animate-dialog-in rounded-card bg-paper p-6 shadow-lift ring-1 ring-sand sm:p-7">
            <Dialog.Title className="font-serif text-2xl font-semibold tracking-tight text-olive">
              Share this recipe
            </Dialog.Title>
            <Dialog.Description className="mt-2 text-sm leading-relaxed text-olive/70">
              Copy the link, or send it to a friend.
            </Dialog.Description>
            <div className="mt-5 flex gap-2">
              <input
                type="text"
                readOnly
                aria-label="Recipe link"
                value={links.url}
                onFocus={(e) => e.currentTarget.select()}
                className="min-w-0 flex-1 rounded-xl border border-sand bg-paper px-3.5 py-2.5 text-sm text-olive focus:border-olive/60 focus:ring-4 focus:ring-saffron/20 focus:outline-none"
              />
              <Button type="button" variant="primary" onClick={copy} className="!px-4 !py-2">
                <Copy aria-hidden="true" className="size-3.5" />
                Copy link
              </Button>
            </div>
            <div className="mt-4 flex flex-wrap gap-2">
              <a className={linkClass} href={links.facebook} target="_blank" rel="noopener noreferrer">
                Facebook{' '}<NewTab />
              </a>
              <a className={linkClass} href={links.x} target="_blank" rel="noopener noreferrer">
                X{' '}<NewTab />
              </a>
              <a className={linkClass} href={links.whatsapp} target="_blank" rel="noopener noreferrer">
                WhatsApp{' '}<NewTab />
              </a>
              <a className={linkClass} href={links.pinterest} target="_blank" rel="noopener noreferrer">
                Pinterest{' '}<NewTab />
              </a>
              <a className={linkClass} href={links.email}>
                <Mail aria-hidden="true" className="size-3.5" />
                Email
              </a>
            </div>
            <div className="mt-6 flex flex-wrap justify-end gap-2">
              {typeof navigator.share === 'function' && (
                <Button type="button" variant="ghost" onClick={shareNatively}>More options</Button>
              )}
              <Dialog.Close asChild>
                <Button type="button" variant="outline">Close</Button>
              </Dialog.Close>
            </div>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </>
  );
}
