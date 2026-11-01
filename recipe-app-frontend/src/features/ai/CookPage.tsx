import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowUpRight, LoaderCircle, RefreshCw } from 'lucide-react';
import {
  aiRecipeQueryKey, aiUsageQueryKey, generateRecipe, getAiRecipe, getAiUsage, type GenerateBody,
} from '@/api/ai';
import { ApiError } from '@/api/client';
import { useAuth } from '@/auth/AuthProvider';
import { Button } from '@/components/ui';
import { FormError } from '@/features/auth/AuthCard';
import { IngredientChipInput } from '@/features/preferences/IngredientChipInput';
import type { GeneratedRecipe, MealType } from '@/lib/types';
import { aiErrorMessage } from './aiMessages';
import { AiRecipeView } from './AiRecipeView';
import { PhotoScan } from './PhotoScan';
import { SaveAiRecipeButton } from './SaveAiRecipeButton';

const MAX_INGREDIENTS = 15;
const MIN_INGREDIENTS = 2;

const MEALS: { value: MealType | null; label: string }[] = [
  { value: null, label: 'Any' },
  { value: 'breakfast', label: 'Breakfast' },
  { value: 'lunch', label: 'Lunch' },
  { value: 'dinner', label: 'Dinner' },
];

const legendClass = 'mb-2 text-[13px] font-semibold text-olive';

/** A radio dressed as a toggle chip; the native input stays in the tab order (sr-only). */
const radioChip =
  'inline-flex cursor-pointer items-center rounded-full border border-sand bg-paper px-3.5 py-1.5 text-sm font-medium text-olive transition select-none hover:border-olive/40 hover:bg-cream has-checked:border-olive has-checked:bg-olive has-checked:text-cream has-checked:shadow-button has-checked:hover:bg-olive/90 has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-olive';

const outlineLink =
  'inline-flex items-center justify-center gap-2 rounded-full border border-olive/20 bg-paper px-4 py-2 text-sm font-semibold whitespace-nowrap text-olive transition duration-200 hover:border-olive/50 hover:bg-cream focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-olive';

/** The generated recipe with its actions. The recipe is read through the cache so Save/Saved stays current. */
function CookResult({
  recipe, onAgain, busy,
}: { recipe: GeneratedRecipe; onAgain: () => void; busy: boolean }) {
  // The fresh recipe seeds the cache; Save/Unsave invalidate it, which refetches the saved flag.
  const query = useQuery({
    queryKey: aiRecipeQueryKey(recipe.id),
    queryFn: () => getAiRecipe(recipe.id),
    initialData: recipe,
    staleTime: Infinity,
  });
  const current = query.data;

  return (
    <AiRecipeView
      recipe={current}
      actions={
        <>
          <Button type="button" variant="outline" disabled={busy} onClick={onAgain} className="!px-4 !py-2">
            <RefreshCw aria-hidden="true" className="size-3.5" strokeWidth={2} />
            Generate another
          </Button>
          <SaveAiRecipeButton id={current.id} saved={current.saved} />
          <Link to={`/ai-recipes/${encodeURIComponent(current.id)}`} className={outlineLink}>
            Open
            <ArrowUpRight aria-hidden="true" className="size-3.5" strokeWidth={2} />
          </Link>
        </>
      }
    />
  );
}

export default function CookPage() {
  const { profile } = useAuth();
  const userId = profile?.id;
  const queryClient = useQueryClient();

  const [ingredients, setIngredients] = useState<string[]>([]);
  const [mealType, setMealType] = useState<MealType | null>(null);
  const [onlyWhatIHave, setOnlyWhatIHave] = useState(false);
  const [flagged, setFlagged] = useState<Record<string, string>>({});
  const [limitReached, setLimitReached] = useState(false);
  const [remaining, setRemaining] = useState<number | null>(null); // the latest count the server gave us
  const lastBody = useRef<GenerateBody | null>(null);
  const resultRef = useRef<HTMLDivElement>(null);

  const usage = useQuery({ queryKey: aiUsageQueryKey(userId), queryFn: getAiUsage });
  const left = remaining ?? usage.data?.remaining ?? null;

  const generate = useMutation({
    mutationFn: generateRecipe,
    onMutate: () => setFlagged({}),
    onSuccess: (res) => {
      setRemaining(res.remainingToday);
      void queryClient.invalidateQueries({ queryKey: aiUsageQueryKey(userId) });
    },
    onError: (err) => {
      if (!(err instanceof ApiError)) return;
      if (err.code === 'daily_limit') {
        setLimitReached(true);
        setRemaining(0);
      }
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
    <div className="mx-auto max-w-2xl">
      <h1 className="font-serif text-4xl leading-[1.05] font-semibold tracking-tight text-olive sm:text-5xl">
        Cook with what I have
      </h1>
      <p className="mt-3 text-[15px] leading-relaxed text-olive/80">
        Add {MIN_INGREDIENTS} to {MAX_INGREDIENTS} ingredients and get a recipe made for you, in line with your food
        preferences.
      </p>

      <form onSubmit={onSubmit} className="mt-8 space-y-7">
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
    </div>
  );
}
