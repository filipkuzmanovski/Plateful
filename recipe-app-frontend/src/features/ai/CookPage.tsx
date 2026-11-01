import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { LoaderCircle } from 'lucide-react';
import { aiUsageQueryKey, generateRecipe, getAiUsage, type GenerateBody } from '@/api/ai';
import { ApiError } from '@/api/client';
import { useAuth } from '@/auth/AuthProvider';
import { Button } from '@/components/ui';
import { FormError } from '@/features/auth/AuthCard';
import { IngredientChipInput } from '@/features/preferences/IngredientChipInput';
import type { MealType } from '@/lib/types';
import { aiErrorMessage } from './aiMessages';
import { CookResult } from './CookResult';
import { PhotoScan } from './PhotoScan';
import { PostImport } from './PostImport';

const MAX_INGREDIENTS = 15;
const MIN_INGREDIENTS = 2;

const MEALS: { value: MealType | null; label: string }[] = [
  { value: null, label: 'Any' },
  { value: 'breakfast', label: 'Breakfast' },
  { value: 'lunch', label: 'Lunch' },
  { value: 'dinner', label: 'Dinner' },
];

const TABS = [
  {
    key: 'ingredients',
    label: 'From my ingredients',
    intro: `Add ${MIN_INGREDIENTS} to ${MAX_INGREDIENTS} ingredients and get a recipe made for you, in line with your food preferences.`,
  },
  {
    key: 'post',
    label: 'From a post',
    intro: 'Saw something good on Instagram? Paste the caption or add screenshots, and get a recipe made for you.',
  },
] as const;

type TabKey = (typeof TABS)[number]['key'];

const tabId = (key: TabKey) => `cook-tab-${key}`;
const panelId = (key: TabKey) => `cook-panel-${key}`;

const legendClass = 'mb-2 text-[13px] font-semibold text-olive';

/** A radio dressed as a toggle chip; the native input stays in the tab order (sr-only). */
const radioChip =
  'inline-flex cursor-pointer items-center rounded-full border border-sand bg-paper px-3.5 py-1.5 text-sm font-medium text-olive transition select-none hover:border-olive/40 hover:bg-cream has-checked:border-olive has-checked:bg-olive has-checked:text-cream has-checked:shadow-button has-checked:hover:bg-olive/90 has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-olive';

/** What both tabs share: the generations left today and whether today's limit was hit. */
interface CounterProps {
  left: number | null;
  limitReached: boolean;
  onRemaining: (n: number) => void;
  onLimit: () => void;
}

/** The "From my ingredients" tab: chips (typed or scanned from photos), meal and the only-what-I-have switch. */
function IngredientsPanel({ left, limitReached, onRemaining, onLimit }: CounterProps) {
  const [ingredients, setIngredients] = useState<string[]>([]);
  const [mealType, setMealType] = useState<MealType | null>(null);
  const [onlyWhatIHave, setOnlyWhatIHave] = useState(false);
  const [flagged, setFlagged] = useState<Record<string, string>>({});
  const lastBody = useRef<GenerateBody | null>(null);
  const resultRef = useRef<HTMLDivElement>(null);

  const generate = useMutation({
    mutationFn: generateRecipe,
    onMutate: () => setFlagged({}),
    onSuccess: (res) => onRemaining(res.remainingToday),
    onError: (err) => {
      if (!(err instanceof ApiError)) return;
      if (err.code === 'daily_limit') onLimit();
      if (err.conflicts?.length) setFlagged(Object.fromEntries(err.conflicts.map((c) => [c.ingredient, c.reason])));
    },
  });

  const result = generate.data?.recipe;
  useEffect(() => {
    if (result) resultRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  }, [result?.id]);

  function changeIngredients(next: string[]) {
    setIngredients(next);
    setFlagged({});
  }

  function run(body: GenerateBody) {
    lastBody.current = body;
    generate.mutate(body);
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!canGenerate) return;
    run({ ingredients, mealType, onlyWhatIHave });
  }

  const canGenerate = ingredients.length >= MIN_INGREDIENTS && !generate.isPending && !limitReached;

  return (
    <>
      <form onSubmit={onSubmit} className="space-y-7">
        <PhotoScan current={ingredients} max={MAX_INGREDIENTS} onFound={changeIngredients} />

        <IngredientChipInput
          label="Ingredients"
          values={ingredients}
          onChange={changeIngredients}
          max={MAX_INGREDIENTS}
          flagged={flagged}
        />

        <fieldset>
          <legend className={legendClass}>Meal</legend>
          <div className="flex flex-wrap gap-2">
            {MEALS.map((m) => (
              <label key={m.label} className={radioChip}>
                <input
                  type="radio"
                  name="meal"
                  className="sr-only"
                  checked={mealType === m.value}
                  onChange={() => setMealType(m.value)}
                />
                {m.label}
              </label>
            ))}
          </div>
        </fieldset>

        <label className="flex cursor-pointer items-center gap-3 text-sm font-medium text-olive select-none">
          <input
            type="checkbox"
            role="switch"
            checked={onlyWhatIHave}
            onChange={(e) => setOnlyWhatIHave(e.target.checked)}
            className="peer sr-only"
          />
          <span
            aria-hidden="true"
            className="relative h-6 w-11 shrink-0 rounded-full border border-sand bg-cream transition peer-checked:border-olive peer-checked:bg-olive peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-olive after:absolute after:top-0.5 after:left-0.5 after:size-4.5 after:rounded-full after:bg-olive/60 after:transition-transform peer-checked:after:translate-x-5 peer-checked:after:bg-cream"
          />
          Only use what I have
        </label>

        {generate.isError && <FormError>{aiErrorMessage(generate.error)}</FormError>}

        <div>
          <Button type="submit" disabled={!canGenerate} className="w-full sm:w-auto sm:min-w-44">
            {generate.isPending && <LoaderCircle aria-hidden="true" className="size-4 animate-spin" />}
            Generate
          </Button>
          {left !== null && <p className="mt-2 text-xs text-olive/70">{left} left today</p>}
        </div>
      </form>

      {generate.isPending && (
        <div className="mt-10" role="status">
          <p className="mb-4 text-sm font-medium text-olive">Creating your recipe. This can take up to a minute.</p>
          <div aria-hidden="true" className="animate-pulse space-y-4 rounded-card bg-paper p-6 shadow-card ring-1 ring-sand/70">
            <div className="h-7 w-2/3 rounded-lg bg-sand/70" />
            <div className="h-4 w-full rounded bg-sand/60" />
            <div className="h-4 w-5/6 rounded bg-sand/60" />
            <div className="h-24 rounded-xl bg-sand/50" />
          </div>
        </div>
      )}

      {result && (
        <div ref={resultRef} className="mt-12 scroll-mt-24">
          <CookResult
            key={result.id}
            recipe={result}
            busy={generate.isPending}
            onAgain={() => { if (lastBody.current && !limitReached) run(lastBody.current); }}
          />
        </div>
      )}
    </>
  );
}

/**
 * "Cook with what I have": a recipe from the user's ingredients or from an Instagram post, one tab each. The tab
 * lives in the URL (`?tab=post`; anything else is the ingredients tab). Both panels stay mounted so the chips, the
 * caption, the screenshots and a running request survive switching. The page owns the shared daily count.
 */
export default function CookPage() {
  const { profile } = useAuth();
  const userId = profile?.id;
  const queryClient = useQueryClient();

  const [params, setParams] = useSearchParams();
  const tabParam = params.get('tab');
  const selected: TabKey = TABS.find((t) => t.key === tabParam)?.key ?? 'ingredients';
  const tabRefs = useRef<Record<TabKey, HTMLButtonElement | null>>({ ingredients: null, post: null });

  const [limitReached, setLimitReached] = useState(false);
  const [remaining, setRemaining] = useState<number | null>(null); // the latest count the server gave us

  const usage = useQuery({ queryKey: aiUsageQueryKey(userId), queryFn: getAiUsage });
  const left = remaining ?? usage.data?.remaining ?? null;

  const counter: CounterProps = {
    left,
    limitReached,
    onRemaining: (n) => {
      setRemaining(n);
      void queryClient.invalidateQueries({ queryKey: aiUsageQueryKey(userId) });
    },
    onLimit: () => {
      setLimitReached(true);
      setRemaining(0);
    },
  };

  function select(key: TabKey) {
    const next = new URLSearchParams(params);
    if (key === 'ingredients') next.delete('tab');
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

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="font-serif text-4xl leading-[1.05] font-semibold tracking-tight text-olive sm:text-5xl">
        Cook with what I have
      </h1>
      <p className="mt-3 text-[15px] leading-relaxed text-olive/80">
        {TABS.find((t) => t.key === selected)?.intro}
      </p>

      <div
        role="tablist"
        aria-label="How to start"
        onKeyDown={onKeyDown}
        className="mt-7 inline-flex max-w-full items-center gap-1 rounded-full max-sm:overflow-x-auto bg-paper p-1 shadow-card ring-1 ring-sand/70"
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
              {label}
            </button>
          );
        })}
      </div>

      {/* Both mounted; the inactive one is hidden, which also keeps it out of the accessibility tree. */}
      {TABS.map(({ key }) => (
        <div
          key={key}
          role="tabpanel"
          id={panelId(key)}
          aria-labelledby={tabId(key)}
          tabIndex={0}
          hidden={key !== selected}
          className="mt-8"
        >
          {key === 'ingredients' ? <IngredientsPanel {...counter} /> : <PostImport {...counter} />}
        </div>
      ))}
    </div>
  );
}
