/**
 * The serif name banner shared by the profile pages: a large initial circle beside the name,
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
        className="flex size-16 shrink-0 items-center justify-center rounded-full bg-ink font-serif text-3xl font-medium text-on-ink sm:size-20 sm:text-4xl"
      >
        {firstName.charAt(0).toUpperCase()}
      </span>
      <div className="min-w-0">
        <h1 className="font-serif text-4xl leading-tight font-medium tracking-[-0.015em] break-words text-ink sm:text-5xl">
          {firstName} {lastName}
        </h1>
        {recipeTotal !== undefined && recipeTotal > 0 && (
          <p className="mt-1 text-[15px] text-ink-soft tabular-nums">
            {recipeTotal} {recipeTotal === 1 ? 'recipe' : 'recipes'} shared
          </p>
        )}
      </div>
    </header>
  );
}
