import type { ReactNode } from 'react';
import * as Select from '@radix-ui/react-select';
import { Check, type LucideIcon } from 'lucide-react';

/**
 * The shared dropdown surface (Radix Select): a surface panel lifted off the page, items that
 * light up in tint, and a check on the chosen one. Triggers are styled where they are used.
 */
export function SelectMenuContent({
  children,
  className = '',
  onCloseAutoFocus,
  onEscapeKeyDown,
  onPointerDownOutside,
}: {
  children: ReactNode;
  className?: string;
  onCloseAutoFocus?: (event: Event) => void;
  onEscapeKeyDown?: (event: KeyboardEvent) => void;
  onPointerDownOutside?: (event: Event) => void;
}) {
  return (
    <Select.Portal>
      <Select.Content
        position="popper"
        sideOffset={6}
        collisionPadding={12}
        onCloseAutoFocus={onCloseAutoFocus}
        onEscapeKeyDown={onEscapeKeyDown}
        onPointerDownOutside={onPointerDownOutside}
        className={`z-50 max-h-[min(var(--radix-select-content-available-height),20rem)] min-w-(--radix-select-trigger-width) origin-(--radix-select-content-transform-origin) overflow-hidden rounded-card border border-rule bg-surface shadow-float data-[state=open]:animate-menu-in ${className}`}
      >
        <Select.Viewport className="p-1.5">{children}</Select.Viewport>
      </Select.Content>
    </Select.Portal>
  );
}

export function SelectMenuItem({
  value,
  icon: Icon,
  children,
}: {
  value: string;
  icon?: LucideIcon;
  children: ReactNode;
}) {
  return (
    <Select.Item
      value={value}
      className="relative flex cursor-pointer items-center gap-2.5 rounded-lg py-2 pr-9 pl-3 text-[15px] font-medium text-ink outline-0 select-none data-[highlighted]:bg-tint data-[highlighted]:outline-2 data-[highlighted]:-outline-offset-2 data-[highlighted]:outline-ink data-[state=checked]:font-semibold"
    >
      {Icon && <Icon aria-hidden="true" className="size-4 shrink-0 text-ink-soft" strokeWidth={2} />}
      <Select.ItemText>{children}</Select.ItemText>
      <Select.ItemIndicator className="absolute right-3 inline-flex">
        <Check aria-hidden="true" className="size-4 text-ink" strokeWidth={2.5} />
      </Select.ItemIndicator>
    </Select.Item>
  );
}
