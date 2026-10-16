import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, useFieldArray, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { toast } from 'sonner';
import { Check, CircleAlert, LockKeyhole, Plus, X } from 'lucide-react';
import { createRecipe, getRecipe, updateRecipe } from '@/api/recipes';
import { ApiError } from '@/api/client';
import { useAuth } from '@/auth/AuthProvider';
import { Button, EmptyState, Field, Spinner, TextInput } from '@/components/ui';
import { deletePhotos, pathFromPublicUrl } from '@/api/photos';
import { CuisinePicker } from './CuisinePicker';
import { PhotoPicker } from './PhotoPicker';
import type { RecipeCreate } from '@/lib/types';

// Mirrors the backend's @PositiveOrZero Integer; empty = not set.
const wholeNumber = z.string().regex(/^\d*$/, 'Whole number ≥ 0');

const schema = z.object({
  title: z.string().trim().min(1, 'Title is required').max(255),
  servings: wholeNumber,
  readyInMinutes: wholeNumber,
  cookingMinutes: wholeNumber,
  preparationMinutes: wholeNumber,
  photos: z.array(z.string()).max(6),
  instructions: z.string().max(50000),
  steps: z.array(z.object({ text: z.string().min(1, 'Step text required').max(5000) })).max(100),
  dairyFree: z.boolean(),
  glutenFree: z.boolean(),
  vegan: z.boolean(),
  vegetarian: z.boolean(),
  cuisines: z.array(z.string()).max(10),
  ingredients: z
    .array(
      z.object({
        ingredientName: z.string().min(1, 'Name required').max(255),
        originalText: z.string().min(1, 'Text required').max(500),
      }),
    )
    .min(1, 'At least one ingredient')
    .max(100),
});
type FormValues = z.infer<typeof schema>;

const emptyValues: FormValues = {
  title: '', servings: '', readyInMinutes: '', cookingMinutes: '',
  preparationMinutes: '', photos: [], instructions: '',
  steps: [], dairyFree: false, glutenFree: false, vegan: false, vegetarian: false,
  cuisines: [], ingredients: [{ ingredientName: '', originalText: '' }],
};

const DIET_LABELS = {
  vegan: 'Vegan',
  vegetarian: 'Vegetarian',
  glutenFree: 'Gluten free',
  dairyFree: 'Dairy free',
} as const;

/** A rounded paper card holding one part of the form. */
function FormSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-card bg-paper p-5 shadow-card ring-1 ring-sand/70 sm:p-7">
      <h2 className="mb-5 font-serif text-2xl font-semibold tracking-tight text-olive">{title}</h2>
      {children}
    </section>
  );
}

/** A checkbox dressed as a toggle chip; the native input stays in the tab order (sr-only). */
const checkChip =
  'group inline-flex cursor-pointer items-center gap-1.5 rounded-full border border-sand bg-paper px-3.5 py-1.5 text-sm font-medium text-olive transition select-none hover:border-olive/40 hover:bg-cream has-checked:border-olive has-checked:bg-olive has-checked:text-cream has-checked:shadow-button has-checked:hover:border-olive has-checked:hover:bg-olive/90 has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-olive';
const inlineError = 'mt-1 block text-xs font-medium text-danger';
const removeButton = '!size-10 shrink-0 !p-0 !text-olive/70 hover:!bg-danger/10 hover:!text-danger';

const toInt = (s: string) => (s.trim() === '' ? null : Number(s));
const toNull = (s: string) => (s.trim() === '' ? null : s);
const isPath = (p: string | null): p is string => p !== null;
/** Storage paths of a recipe's photos; imported (non-bucket) URLs have none and are dropped. */
const bucketPaths = (urls: string[]) => urls.map(pathFromPublicUrl).filter(isPath);

function toDto(v: FormValues): RecipeCreate {
  return {
    title: v.title,
    servings: toInt(v.servings),
    readyInMinutes: toInt(v.readyInMinutes),
    cookingMinutes: toInt(v.cookingMinutes),
    preparationMinutes: toInt(v.preparationMinutes),
    photos: v.photos,
    instructions: toNull(v.instructions),
    instructionsSteps: v.steps.map((s, i) => ({ number: i + 1, step: s.text, ingredients: [], equipment: [] })),
    dairyFree: v.dairyFree, glutenFree: v.glutenFree, vegan: v.vegan, vegetarian: v.vegetarian,
    cuisines: v.cuisines,
    ingredients: v.ingredients,
  };
}

export default function RecipeFormPage() {
  const { id } = useParams<{ id: string }>(); // present in edit mode only
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { profile } = useAuth();
  const hydratedFor = useRef<string | null>(null); // recipe id the form was last filled from
  const initialPaths = useRef<string[]>([]); // bucket photos the recipe had when the form was filled
  const [photosBusy, setPhotosBusy] = useState(false);

  const existing = useQuery({
    queryKey: ['recipe', id],
    queryFn: () => getRecipe(id!),
    enabled: !!id,
  });

  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: emptyValues });
  const { register, handleSubmit, control, reset, setError, formState: { errors, isSubmitting } } = form;
  const ingredients = useFieldArray({ control, name: 'ingredients' });
  const steps = useFieldArray({ control, name: 'steps' });

  useEffect(() => {
    if (existing.data && hydratedFor.current !== existing.data.id) {
      hydratedFor.current = existing.data.id;
      const r = existing.data;
      initialPaths.current = bucketPaths(r.photos);
      reset({
        title: r.title, servings: r.servings?.toString() ?? '',
        readyInMinutes: r.readyInMinutes?.toString() ?? '', cookingMinutes: r.cookingMinutes?.toString() ?? '',
        preparationMinutes: r.preparationMinutes?.toString() ?? '',
        photos: initialPaths.current, instructions: r.instructions ?? '',
        steps: r.instructionsSteps.map((s) => ({ text: s.step })),
        dairyFree: r.dairyFree, glutenFree: r.glutenFree, vegan: r.vegan, vegetarian: r.vegetarian,
        cuisines: r.cuisines,
        ingredients: r.ingredients.length
          ? r.ingredients.map((i) => ({ ingredientName: i.ingredientName, originalText: i.originalText }))
          : [{ ingredientName: '', originalText: '' }],
      });
    }
  }, [existing.data, reset]);

  async function onSubmit(values: FormValues) {
    try {
      const saved = id ? await updateRecipe(id, toDto(values)) : await createRecipe(toDto(values));
      // The API returns the full saved recipe: seed the detail cache with it, and mark every
      // list that might show it as stale.
      queryClient.setQueryData(['recipe', saved.id], saved);
      void queryClient.invalidateQueries({ queryKey: ['recipes'] });
      void queryClient.invalidateQueries({ queryKey: ['userRecipes'] });
      void queryClient.invalidateQueries({ queryKey: ['savedRecipes'] });
      // Files the user took off the recipe go only once the update has succeeded; best-effort.
      const removed = id ? initialPaths.current.filter((p) => !values.photos.includes(p)) : [];
      if (removed.length > 0) void deletePhotos(removed);
      navigate(`/recipes/${saved.id}`);
    } catch (e) {
      if (e instanceof ApiError && e.errors) {
        for (const [field, message] of Object.entries(e.errors)) {
          // Backend keys match ours except instructionsSteps[n].x → steps and cuisines[n] → cuisines.
          const name = field.startsWith('instructionsSteps')
            ? 'steps'
            : field.startsWith('cuisines')
              ? 'cuisines'
              : field;
          setError(name as never, { message });
        }
      }
      // Always summarize, so a rejected save is never silent.
      toast.error(e instanceof ApiError ? e.detail : 'Something went wrong');
    }
  }

  if (id && existing.isPending) return <Spinner />;
  if (id && existing.isError) {
    return <EmptyState icon={CircleAlert}>Couldn't load this recipe for editing.</EmptyState>;
  }

  if (id && existing.data && existing.data.user?.id !== profile?.id) {
    return <EmptyState icon={LockKeyhole}>You can only edit your own recipes.</EmptyState>;
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="mx-auto max-w-3xl">
      <header className="mb-8">
        <h1 className="font-serif text-4xl leading-tight font-semibold tracking-tight text-olive sm:text-5xl">
          {id ? 'Edit recipe' : 'New recipe'}
        </h1>
        <p className="mt-2 text-olive/70">
          {id ? 'Polish the details and save your changes.' : 'Share something you love to cook.'}
        </p>
      </header>

      <div className="space-y-6">
        <FormSection title="Basics">
          <div className="space-y-4">
            <Field label="Title" error={errors.title?.message}>
              <TextInput aria-label="Title" {...register('title')} />
            </Field>
            <div>
              <Controller
                control={control}
                name="photos"
                render={({ field }) => (
                  <PhotoPicker
                    value={field.value}
                    onChange={field.onChange}
                    userId={profile?.id ?? ''}
                    onBusyChange={setPhotosBusy}
                  />
                )}
              />
              {errors.photos?.message && (
                <p role="alert" className="mt-1.5 text-xs font-medium text-danger">{errors.photos.message}</p>
              )}
            </div>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <Field label="Servings" error={errors.servings?.message}><TextInput type="number" min={0} {...register('servings')} /></Field>
              <Field label="Ready (min)" error={errors.readyInMinutes?.message}><TextInput type="number" min={0} {...register('readyInMinutes')} /></Field>
              <Field label="Cook (min)" error={errors.cookingMinutes?.message}><TextInput type="number" min={0} {...register('cookingMinutes')} /></Field>
              <Field label="Prep (min)" error={errors.preparationMinutes?.message}><TextInput type="number" min={0} {...register('preparationMinutes')} /></Field>
            </div>
          </div>
        </FormSection>

        <FormSection title="Tags">
          <fieldset>
            <legend className="mb-2 text-[13px] font-semibold text-olive">Diet</legend>
            <div className="flex flex-wrap gap-2">
              {(Object.keys(DIET_LABELS) as (keyof typeof DIET_LABELS)[]).map((flag) => (
                <label key={flag} className={checkChip}>
                  <input type="checkbox" className="sr-only" {...register(flag)} />
                  <Check aria-hidden="true" className="hidden size-3.5 group-has-checked:block" strokeWidth={2.5} />
                  {DIET_LABELS[flag]}
                </label>
              ))}
            </div>
          </fieldset>
          <div className="mt-5">
            <Controller
              control={control}
              name="cuisines"
              render={({ field }) => (
                <CuisinePicker value={field.value} onChange={field.onChange} error={errors.cuisines?.message} />
              )}
            />
          </div>
        </FormSection>

        <FormSection title="Ingredients">
          {errors.ingredients?.message && (
            <p role="alert" className="mb-3 text-xs font-medium text-danger">{errors.ingredients.message}</p>
          )}
          <div className="space-y-2.5">
            {ingredients.fields.map((field, i) => (
              <div key={field.id} className="flex items-start gap-2">
                <div className="w-2/5 sm:w-1/3">
                  <TextInput
                    aria-label={`Ingredient ${i + 1} name`}
                    placeholder="Name (e.g. flour)"
                    {...register(`ingredients.${i}.ingredientName`)}
                  />
                  {errors.ingredients?.[i]?.ingredientName && (
                    <span role="alert" className={inlineError}>{errors.ingredients[i]?.ingredientName?.message}</span>
                  )}
                </div>
                <div className="min-w-0 flex-1">
                  <TextInput
                    aria-label={`Ingredient ${i + 1} text`}
                    placeholder="As written (e.g. 2 cups flour, sifted)"
                    {...register(`ingredients.${i}.originalText`)}
                  />
                  {errors.ingredients?.[i]?.originalText && (
                    <span role="alert" className={inlineError}>{errors.ingredients[i]?.originalText?.message}</span>
                  )}
                </div>
                <Button
                  type="button"
                  variant="ghost"
                  aria-label={`Remove ingredient ${i + 1}`}
                  className={removeButton}
                  onClick={() => ingredients.remove(i)}
                  disabled={ingredients.fields.length === 1}
                >
                  <X aria-hidden="true" className="size-4" />
                </Button>
              </div>
            ))}
          </div>
          <Button type="button" variant="outline" className="mt-4" onClick={() => ingredients.append({ ingredientName: '', originalText: '' })}>
            <Plus aria-hidden="true" className="size-4" />
            Add ingredient
          </Button>
        </FormSection>

        <FormSection title="Instructions">
          <Field label="Free-text instructions" error={errors.instructions?.message}>
            <textarea
              rows={5}
              className="w-full rounded-xl border border-sand bg-paper px-3.5 py-2.5 text-sm leading-relaxed text-olive shadow-[inset_0_1px_2px_rgb(47_58_43/0.04)] transition placeholder:text-olive/70 hover:border-olive/30 focus:border-olive/60 focus:ring-4 focus:ring-saffron/20 focus:outline-none"
              {...register('instructions')}
            />
          </Field>
          <p className="mt-6 mb-2 text-[13px] font-semibold text-olive">
            Structured steps <span className="font-normal text-olive/70">(optional)</span>
          </p>
          {(errors.steps?.message ?? errors.steps?.root?.message) && (
            <p role="alert" className="mb-2 text-xs font-medium text-danger">
              {errors.steps?.message ?? errors.steps?.root?.message}
            </p>
          )}
          <div className="space-y-2.5">
            {steps.fields.map((field, i) => (
              <div key={field.id} className="flex items-start gap-3">
                <span className="w-7 shrink-0 pt-1 text-right font-serif text-2xl leading-none font-medium text-terracotta italic tabular-nums">
                  {i + 1}
                </span>
                <div className="min-w-0 flex-1">
                  <TextInput aria-label={`Step ${i + 1}`} {...register(`steps.${i}.text`)} />
                  {errors.steps?.[i]?.text && (
                    <span role="alert" className={inlineError}>{errors.steps[i]?.text?.message}</span>
                  )}
                </div>
                <Button
                  type="button"
                  variant="ghost"
                  aria-label={`Remove step ${i + 1}`}
                  className={removeButton}
                  onClick={() => steps.remove(i)}
                >
                  <X aria-hidden="true" className="size-4" />
                </Button>
              </div>
            ))}
          </div>
          <Button type="button" variant="outline" className="mt-4" onClick={() => steps.append({ text: '' })}>
            <Plus aria-hidden="true" className="size-4" />
            Add step
          </Button>
        </FormSection>
      </div>

      {/* Save stays within reach on long forms. */}
      <div className="sticky bottom-4 z-10 mt-8 flex items-center justify-end gap-3">
        {photosBusy && <p className="rounded-full bg-paper/90 px-3 py-1.5 text-xs text-olive/70 ring-1 ring-sand">Waiting for photos to finish uploading</p>}
        <div className="rounded-full bg-paper/90 p-1.5 shadow-lift ring-1 ring-sand backdrop-blur-md">
          <Button type="submit" disabled={isSubmitting || photosBusy}>Save recipe</Button>
        </div>
      </div>
    </form>
  );
}
