import type { ReactNode } from 'react';
import * as Select from '@radix-ui/react-select';
import { Check, type LucideIcon } from 'lucide-react';

/**
 * The shared dropdown surface (Radix Select): a paper panel lifted off the page, items that
 * light up in cream/sand, and a check on the chosen one. Triggers are styled where they are used.
 */
export function SelectMenuContent({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <Select.Portal>
      <Select.Content
        position="popper"
        sideOffset={6}
        collisionPadding={12}
        className={`z-50 max-h-[min(var(--radix-select-content-available-height),20rem)] min-w-(--radix-select-trigger-width) origin-(--radix-select-content-transform-origin) overflow-hidden rounded-2xl bg-paper shadow-lift ring-1 ring-sand/70 data-[state=open]:animate-menu-in ${className}`}
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
      className="relative flex cursor-pointer items-center gap-2.5 rounded-xl py-2 pr-9 pl-3 text-sm font-medium text-olive outline-none select-none data-[highlighted]:bg-sand/60 data-[state=checked]:font-semibold"
    >
      {Icon && <Icon aria-hidden="true" className="size-4 shrink-0 text-olive/70" strokeWidth={2} />}
      <Select.ItemText>{children}</Select.ItemText>
      <Select.ItemIndicator className="absolute right-3 inline-flex">
        <Check aria-hidden="true" className="size-4 text-terracotta" strokeWidth={2.5} />
      </Select.ItemIndicator>
    </Select.Item>
  );
}
