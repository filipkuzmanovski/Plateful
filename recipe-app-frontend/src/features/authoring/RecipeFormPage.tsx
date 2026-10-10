import { useEffect, useRef } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useFieldArray, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { toast } from 'sonner';
import { createRecipe, getRecipe, updateRecipe } from '@/api/recipes';
import { ApiError } from '@/api/client';
import { Button, Field, SectionHeading, Spinner, TextInput } from '@/components/ui';
import { CUISINES, cuisineLabel } from '@/features/browse/FilterBar';
import type { RecipeCreate } from '@/lib/types';

// Mirrors the backend's @PositiveOrZero Integer; empty = not set.
const wholeNumber = z.string().regex(/^\d*$/, 'Whole number ≥ 0');

const schema = z.object({
  title: z.string().trim().min(1, 'Title is required').max(255),
  image: z.string().max(2048),
  servings: wholeNumber,
  readyInMinutes: wholeNumber,
  cookingMinutes: wholeNumber,
  preparationMinutes: wholeNumber,
  sourceName: z.string().max(255),
  sourceUrl: z.string().max(2048),
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
  title: '', image: '', servings: '', readyInMinutes: '', cookingMinutes: '',
  preparationMinutes: '', sourceName: '', sourceUrl: '', instructions: '',
  steps: [], dairyFree: false, glutenFree: false, vegan: false, vegetarian: false,
  cuisines: [], ingredients: [{ ingredientName: '', originalText: '' }],
};

const toInt = (s: string) => (s.trim() === '' ? null : Number(s));
const toNull = (s: string) => (s.trim() === '' ? null : s);

function toDto(v: FormValues): RecipeCreate {
  return {
    title: v.title,
    image: toNull(v.image),
    servings: toInt(v.servings),
    readyInMinutes: toInt(v.readyInMinutes),
    cookingMinutes: toInt(v.cookingMinutes),
    preparationMinutes: toInt(v.preparationMinutes),
    sourceName: toNull(v.sourceName),
    sourceUrl: toNull(v.sourceUrl),
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
  const hydratedFor = useRef<string | null>(null); // recipe id the form was last filled from

  const existing = useQuery({
    queryKey: ['recipe', id],
    queryFn: () => getRecipe(id!),
    enabled: !!id,
  });

  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: emptyValues });
  const { register, handleSubmit, control, reset, setError, watch, formState: { errors, isSubmitting } } = form;
  const ingredients = useFieldArray({ control, name: 'ingredients' });
  const steps = useFieldArray({ control, name: 'steps' });
  const imageUrl = watch('image');

  useEffect(() => {
    if (existing.data && hydratedFor.current !== existing.data.id) {
      hydratedFor.current = existing.data.id;
      const r = existing.data;
      reset({
        title: r.title, image: r.image ?? '', servings: r.servings?.toString() ?? '',
        readyInMinutes: r.readyInMinutes?.toString() ?? '', cookingMinutes: r.cookingMinutes?.toString() ?? '',
        preparationMinutes: r.preparationMinutes?.toString() ?? '', sourceName: r.sourceName ?? '',
        sourceUrl: r.sourceUrl ?? '', instructions: r.instructions ?? '',
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
    return <p className="py-12 text-center text-muted">Couldn't load this recipe for editing.</p>;
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="mx-auto max-w-2xl">
      <h1 className="text-2xl font-extrabold tracking-tight">{id ? 'Edit recipe' : 'New recipe'}</h1>

      <SectionHeading>Basics</SectionHeading>
      <div className="space-y-4">
        <Field label="Title" error={errors.title?.message}>
          <TextInput aria-label="Title" {...register('title')} />
        </Field>
        <Field label="Image URL" error={errors.image?.message}>
          <TextInput aria-label="Image URL" {...register('image')} />
        </Field>
        {imageUrl && <img src={imageUrl} alt="Preview" className="h-40 object-cover" />}
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Field label="Servings" error={errors.servings?.message}><TextInput type="number" min={0} {...register('servings')} /></Field>
          <Field label="Ready (min)" error={errors.readyInMinutes?.message}><TextInput type="number" min={0} {...register('readyInMinutes')} /></Field>
          <Field label="Cook (min)" error={errors.cookingMinutes?.message}><TextInput type="number" min={0} {...register('cookingMinutes')} /></Field>
          <Field label="Prep (min)" error={errors.preparationMinutes?.message}><TextInput type="number" min={0} {...register('preparationMinutes')} /></Field>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Source name" error={errors.sourceName?.message}><TextInput {...register('sourceName')} /></Field>
          <Field label="Source URL" error={errors.sourceUrl?.message}><TextInput {...register('sourceUrl')} /></Field>
        </div>
      </div>

      <SectionHeading>Tags</SectionHeading>
      <div className="flex flex-wrap gap-4 text-sm">
        {(['vegan', 'vegetarian', 'glutenFree', 'dairyFree'] as const).map((flag) => (
          <label key={flag} className="flex items-center gap-1">
            <input type="checkbox" className="accent-ink" {...register(flag)} /> {flag}
          </label>
        ))}
      </div>
      <div className="mt-3 flex flex-wrap gap-3 text-sm">
        {CUISINES.map((c) => (
          <label key={c} className="flex items-center gap-1">
            <input type="checkbox" value={c} className="accent-ink" {...register('cuisines')} /> {cuisineLabel(c)}
          </label>
        ))}
      </div>
      {errors.cuisines?.message && (
        <p role="alert" className="mt-1 text-xs text-red-600">{errors.cuisines.message}</p>
      )}

      <SectionHeading>Ingredients</SectionHeading>
      {errors.ingredients?.message && <p role="alert" className="mb-2 text-xs text-red-600">{errors.ingredients.message}</p>}
      <div className="space-y-2">
        {ingredients.fields.map((field, i) => (
          <div key={field.id} className="flex gap-2">
            <div className="w-1/3">
              <TextInput
                aria-label={`Ingredient ${i + 1} name`}
                placeholder="Name (e.g. flour)"
                {...register(`ingredients.${i}.ingredientName`)}
              />
              {errors.ingredients?.[i]?.ingredientName && (
                <span role="alert" className="text-xs text-red-600">{errors.ingredients[i]?.ingredientName?.message}</span>
              )}
            </div>
            <div className="flex-1">
              <TextInput
                aria-label={`Ingredient ${i + 1} text`}
                placeholder="As written (e.g. 2 cups flour, sifted)"
                {...register(`ingredients.${i}.originalText`)}
              />
              {errors.ingredients?.[i]?.originalText && (
                <span role="alert" className="text-xs text-red-600">{errors.ingredients[i]?.originalText?.message}</span>
              )}
            </div>
            <Button type="button" variant="outline" onClick={() => ingredients.remove(i)} disabled={ingredients.fields.length === 1}>✕</Button>
          </div>
        ))}
      </div>
      <Button type="button" variant="outline" className="mt-2" onClick={() => ingredients.append({ ingredientName: '', originalText: '' })}>
        Add ingredient
      </Button>

      <SectionHeading>Instructions</SectionHeading>
      <Field label="Free-text instructions" error={errors.instructions?.message}>
        <textarea
          rows={4}
          className="w-full border border-hairline px-3 py-2 text-sm focus:border-ink focus:outline-none"
          {...register('instructions')}
        />
      </Field>
      <p className="mt-3 mb-1 text-xs font-bold tracking-widest uppercase">Structured steps (optional)</p>
      {(errors.steps?.message ?? errors.steps?.root?.message) && (
        <p role="alert" className="mb-1 text-xs text-red-600">
          {errors.steps?.message ?? errors.steps?.root?.message}
        </p>
      )}
      <div className="space-y-2">
        {steps.fields.map((field, i) => (
          <div key={field.id} className="flex gap-2">
            <span className="pt-2 text-sm font-extrabold">{i + 1}.</span>
            <div className="flex-1">
              <TextInput aria-label={`Step ${i + 1}`} {...register(`steps.${i}.text`)} />
              {errors.steps?.[i]?.text && (
                <span role="alert" className="text-xs text-red-600">{errors.steps[i]?.text?.message}</span>
              )}
            </div>
            <Button type="button" variant="outline" onClick={() => steps.remove(i)}>✕</Button>
          </div>
        ))}
      </div>
      <Button type="button" variant="outline" className="mt-2" onClick={() => steps.append({ text: '' })}>
        Add step
      </Button>

      <div className="mt-8 border-t-2 border-ink pt-4">
        <Button type="submit" disabled={isSubmitting}>Save recipe</Button>
      </div>
    </form>
  );
}
