/**
 * The serif name banner shared by the profile pages: a large initial medallion beside the name,
 * with a recipe count once it is known.
 */
export function ProfileHeader({
  firstName,
  lastName,
  recipeTotal,
}: {
  firstName: string;
  lastName: string;
  recipeTotal?: number;
}) {
  return (
    <header className="flex items-center gap-4 sm:gap-6">
      <span
        aria-hidden="true"
        className="flex size-16 shrink-0 items-center justify-center rounded-full bg-olive font-serif text-3xl font-semibold text-cream shadow-button ring-4 ring-paper sm:size-20 sm:text-4xl"
      >
        {firstName.charAt(0).toUpperCase()}
      </span>
      <div className="min-w-0">
        <h1 className="font-serif text-3xl leading-tight font-semibold tracking-tight break-words text-olive sm:text-5xl">
          {firstName} {lastName}
        </h1>
        {recipeTotal !== undefined && recipeTotal > 0 && (
          <p className="mt-1 text-olive/70 tabular-nums">
            {recipeTotal} {recipeTotal === 1 ? 'recipe' : 'recipes'} shared
          </p>
        )}
      </div>
    </header>
  );
}
