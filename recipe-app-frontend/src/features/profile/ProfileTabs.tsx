import { useRef, type KeyboardEvent, type ReactNode } from 'react';
import { useSearchParams } from 'react-router';

const TABS = [
  { key: 'mine', label: 'My recipes' },
  { key: 'saved', label: 'Saved' },
  { key: 'ai', label: 'AI recipes' },
  { key: 'preferences', label: 'Preferences' },
] as const;

type TabKey = (typeof TABS)[number]['key'];

const tabId = (key: TabKey) => `profile-tab-${key}`;
const panelId = (key: TabKey) => `profile-panel-${key}`;

/**
 * The "My recipes" / "Saved" / "AI recipes" / "Preferences" tabs on the user's own profile. The selected tab lives in the URL
 * (`?tab=saved`, `?tab=ai` or `?tab=preferences`; anything else is "My recipes"). Arrow keys move focus and select (automatic activation).
 * Only the selected panel's content is rendered, so the Saved and AI recipes lists and preferences are not fetched until opened.
 */
export function ProfileTabs({
  mine,
  saved,
  ai,
  preferences,
  needsAttention = 0,
}: {
  mine: ReactNode;
  saved: ReactNode;
  ai: ReactNode;
  preferences: ReactNode;
  /** Rejected recipes the author should look at; shown in the "My recipes" label when above 0. */
  needsAttention?: number;
}) {
  const [params, setParams] = useSearchParams();
  const tabParam = params.get('tab');
  const selected: TabKey = TABS.find((t) => t.key === tabParam)?.key ?? 'mine';
  const tabRefs = useRef<Record<TabKey, HTMLButtonElement | null>>({ mine: null, saved: null, ai: null, preferences: null });

  function select(key: TabKey) {
    const next = new URLSearchParams(params);
    if (key === 'mine') next.delete('tab');
    else next.set('tab', key);
    setParams(next, { replace: true });
  }

  function onKeyDown(e: KeyboardEvent) {
    if (e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return; // leave Alt+Arrow (browser back/forward) etc. alone
    const current = TABS.findIndex((t) => t.key === selected);
    let target: number;
    switch (e.key) {
      case 'ArrowRight': target = (current + 1) % TABS.length; break;
      case 'ArrowLeft': target = (current - 1 + TABS.length) % TABS.length; break;
      case 'Home': target = 0; break;
      case 'End': target = TABS.length - 1; break;
      default: return;
    }
    e.preventDefault();
    const key = TABS[target].key;
    select(key);
    tabRefs.current[key]?.focus();
  }

  const content: Record<TabKey, ReactNode> = { mine, saved, ai, preferences };

  return (
    <div className="mt-10">
      <div className="mb-6 flex items-center gap-4 after:h-px after:flex-1 after:bg-sand">
        <div
          role="tablist"
          aria-label="Your recipes and preferences"
          onKeyDown={onKeyDown}
          className="inline-flex max-w-full items-center gap-1 rounded-full max-sm:overflow-x-auto bg-paper p-1 shadow-card ring-1 ring-sand/70"
        >
          {TABS.map(({ key, label }) => {
            const active = key === selected;
            return (
              <button
                key={key}
                ref={(el) => { tabRefs.current[key] = el; }}
                type="button"
                role="tab"
                id={tabId(key)}
                aria-selected={active}
                aria-controls={panelId(key)}
                tabIndex={active ? 0 : -1}
                onClick={() => select(key)}
                className={`shrink-0 rounded-full px-3.5 py-2 text-sm font-semibold transition duration-200 sm:px-5 ${
                  active ? 'bg-olive text-cream shadow-button' : 'text-olive hover:bg-sand/60'
                }`}
              >
                {key === 'mine' && needsAttention > 0 ? `${label} · ${needsAttention} needs attention` : label}
              </button>
            );
          })}
        </div>
      </div>
      {TABS.map(({ key }) => (
        <div key={key} role="tabpanel" id={panelId(key)} aria-labelledby={tabId(key)} tabIndex={0} hidden={key !== selected}>
          {key === selected && content[key]}
        </div>
      ))}
    </div>
  );
}
