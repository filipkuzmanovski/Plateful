import { useId, useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Check, CircleAlert } from 'lucide-react';
import { allergensQueryKey, getAllergens } from '@/api/food';
import { ApiError } from '@/api/client';
import { Button } from '@/components/ui';
import { CuisinePicker } from '@/features/authoring/CuisinePicker';
import { FormError } from '@/features/auth/AuthCard';
import { IngredientChipInput } from './IngredientChipInput';
import type { Diet, Preferences } from '@/lib/types';

const MAX_DISLIKED = 30;
const MAX_LIKED = 30;
const MAX_CUISINES = 20;
const ALLERGY_ERROR = 'Choose your allergies, or tick I have no food allergies.';

const DIETS: { key: Diet; label: string }[] = [
  { key: 'vegetarian', label: 'Vegetarian' },
  { key: 'vegan', label: 'Vegan' },
  { key: 'gluten_free', label: 'Gluten-free' },
  { key: 'dairy_free', label: 'Dairy-free' },
];

/** A checkbox dressed as a toggle chip; the native input stays in the tab order (sr-only). */
const checkChip =
  'group inline-flex cursor-pointer items-center gap-1.5 rounded-full border border-sand bg-paper px-3.5 py-1.5 text-sm font-medium text-olive transition select-none hover:border-olive/40 hover:bg-cream has-checked:border-olive has-checked:bg-olive has-checked:text-cream has-checked:shadow-button has-checked:hover:border-olive has-checked:hover:bg-olive/90 has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-olive';

const legendClass = 'mb-2 text-[13px] font-semibold text-olive';

function CheckChip({
  label,
  checked,
  onChange,
}: {
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label className={checkChip}>
      <input type="checkbox" className="sr-only" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <Check aria-hidden="true" className="hidden size-3.5 group-has-checked:block" strokeWidth={2.5} />
      {label}
    </label>
  );
}

/**
 * The food preferences form, shared by the sign-up setup step and (later) the profile. Allergies are
 * required: pick some, or tick "I have no food allergies". `onSave` throws to report a failure; the
 * form shows an ApiError's detail.
 */
export function PreferencesForm({
  initial,
  submitLabel,
  onSave,
}: {
  initial?: Preferences;
  submitLabel: string;
  onSave: (p: Preferences) => Promise<void>;
}) {
  const allergyErrorId = useId();
  const [allergens, setAllergens] = useState<string[]>(initial?.allergens ?? []);
  // Saved preferences with an empty allergen list are the user's "none" answer.
  const [noAllergies, setNoAllergies] = useState(initial !== undefined && initial.allergens.length === 0);
  const [diets, setDiets] = useState<Diet[]>(initial?.diets ?? []);
  const [disliked, setDisliked] = useState<string[]>(initial?.dislikedIngredients ?? []);
  const [liked, setLiked] = useState<string[]>(initial?.likedIngredients ?? []);
  const [cuisines, setCuisines] = useState<string[]>(initial?.likedCuisines ?? []);
  const [allergyError, setAllergyError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const allergenList = useQuery({ queryKey: allergensQueryKey, queryFn: getAllergens, staleTime: Infinity });

  function toggleAllergen(key: string, on: boolean) {
    setAllergens((cur) => (on ? [...cur, key] : cur.filter((k) => k !== key)));
    if (on) setNoAllergies(false);
    setAllergyError(null);
  }

  function toggleNone(on: boolean) {
    setNoAllergies(on);
    if (on) {
      setAllergens([]);
      setAllergyError(null);
    }
  }

  function toggleDiet(key: Diet, on: boolean) {
    setDiets((cur) => (on ? [...cur, key] : cur.filter((k) => k !== key)));
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!noAllergies && allergens.length === 0) {
      setAllergyError(ALLERGY_ERROR);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onSave({
        allergens: noAllergies ? [] : allergens,
        diets,
        dislikedIngredients: disliked,
        likedIngredients: liked,
        likedCuisines: cuisines,
      });
    } catch (err) {
      setError(err instanceof ApiError ? err.detail : 'Something went wrong');
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-7">
      <fieldset aria-describedby={allergyError ? allergyErrorId : undefined}>
        <legend className={legendClass}>Food allergies</legend>
        {allergenList.isError ? (
          <div className="flex flex-wrap items-center gap-3 rounded-xl border border-sand bg-cream px-3.5 py-2.5 text-sm text-olive">
            <CircleAlert aria-hidden="true" className="size-4 shrink-0 text-danger" />
            <span>Couldn't load the allergy list</span>
            <Button type="button" variant="outline" className="!px-3.5 !py-1" onClick={() => void allergenList.refetch()}>
              Retry
            </Button>
          </div>
        ) : allergenList.isPending ? (
          <p role="status" className="text-sm text-olive/70">Loading allergies…</p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {allergenList.data.map((a) => (
              <CheckChip
                key={a.key}
                label={a.label}
                checked={allergens.includes(a.key)}
                onChange={(on) => toggleAllergen(a.key, on)}
              />
            ))}
          </div>
        )}
        <div className="mt-3">
          <CheckChip label="I have no food allergies" checked={noAllergies} onChange={toggleNone} />
        </div>
        {allergyError && (
          <p id={allergyErrorId} role="alert" className="mt-2 text-xs font-medium text-danger">
            {allergyError}
          </p>
        )}
      </fieldset>

      <fieldset>
        <legend className={legendClass}>Diet</legend>
        <div className="flex flex-wrap gap-2">
          {DIETS.map((d) => (
            <CheckChip
              key={d.key}
              label={d.label}
              checked={diets.includes(d.key)}
              onChange={(on) => toggleDiet(d.key, on)}
            />
          ))}
        </div>
      </fieldset>

      <IngredientChipInput
        label="Ingredients I don't eat"
        values={disliked}
        onChange={setDisliked}
        max={MAX_DISLIKED}
      />

      <fieldset className="space-y-5">
        <legend className={legendClass}>What I like</legend>
        <IngredientChipInput label="Favourite ingredients" values={liked} onChange={setLiked} max={MAX_LIKED} />
        <CuisinePicker value={cuisines} onChange={setCuisines} max={MAX_CUISINES} />
      </fieldset>

      {error && <FormError>{error}</FormError>}
      <Button type="submit" disabled={busy} className="w-full">{submitLabel}</Button>
    </form>
  );
}
