import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { RecipeCredit } from '@/features/recipe/RecipeCredit';
import { RecipeInstructions } from '@/features/recipe/RecipeInstructions';
import { ingredientLine } from '@/lib/ingredientLine';
import type { Cookbook, HiddenState, RecipeDisplay, SavedItem } from '@/lib/types';
import { formatMinutes } from './bookFacts';
import { coverClasses, DEFAULT_SUBTITLE } from './constants';

const LABEL = 'font-sans text-[13px] font-semibold tracking-[0.12em] text-accent-ink uppercase';
const SMALL_HEADING = 'mb-3 font-serif text-[22px] leading-tight font-medium text-ink';
const FOCUS_HEADING = 'outline-none';

/**
 * A leaf's two pages on one sheet. Layout lives here and visibility on the leaf around it (see CookbookPage), so
 * the leaf's `hidden` and this `md:grid` never meet on one element. Wide screens show the pages side by side;
 * phones and print stack them.
 */
function Sheet({ children }: { children: ReactNode }) {
  return (
    <div className="overflow-hidden rounded-card border border-rule bg-surface md:grid md:grid-cols-2 md:divide-x md:divide-rule print:block print:rounded-none print:border-0">
      {children}
    </div>
  );
}

function Page({ children }: { children: ReactNode }) {
  return <div className="min-w-0 p-6 sm:p-8">{children}</div>;
}

const chapterTitle = (item: SavedItem) => (item.recipe ? item.recipe.title : item.stub.approvedTitle);

export function CoverLeaf({ book }: { book: Cookbook }) {
  return (
    <div
      className={`mx-auto flex aspect-[3/4] max-w-sm flex-col justify-between rounded-l-sm rounded-r-xl border border-rule p-8 text-center font-serif shadow-[inset_0.75rem_0_0_rgb(0_0_0/0.18)] print:shadow-none ${coverClasses(book.volume)}`}
    >
      <p className="font-sans text-[13px] tracking-[0.14em] uppercase">Volume {book.volume}</p>
      <h1 data-leaf-heading tabIndex={-1} className={`border-y border-on-cover/60 py-6 ${FOCUS_HEADING}`}>
        <span className="block font-sans text-[13px] tracking-[0.14em] uppercase">The cookbook of</span>{' '}
        <span className="mt-2 block text-4xl leading-tight font-medium break-words italic">
          {book.ownerFirstName} {book.ownerLastName}
        </span>
      </h1>
      <p className="text-lg break-words italic">{book.subtitle ?? DEFAULT_SUBTITLE}</p>
    </div>
  );
}

export function OpeningLeaf({
  book,
  facts,
  collected,
  firstChapterLeaf,
}: {
  book: Cookbook;
  facts: { cuisines: string[]; totalMinutes: number };
  /** The date line, such as "July 2026". */
  collected: string;
  firstChapterLeaf: number;
}) {
  return (
    <Sheet>
      <Page>
        <h2
          data-leaf-heading
          tabIndex={-1}
          className={`font-serif text-3xl leading-tight font-medium text-balance text-ink ${FOCUS_HEADING}`}
        >
          Collected by {book.ownerFirstName}, {collected}
        </h2>
        {(facts.cuisines.length > 0 || facts.totalMinutes > 0) && (
          <dl className="mt-6 space-y-4">
            {facts.cuisines.length > 0 && (
              <div>
                <dt className={LABEL}>Cuisines</dt>
                <dd className="mt-1 text-base text-ink">{facts.cuisines.join(', ')}</dd>
              </div>
            )}
            {facts.totalMinutes > 0 && (
              <div>
                <dt className={LABEL}>Total time</dt>
                <dd className="mt-1 text-base text-ink tabular-nums">{formatMinutes(facts.totalMinutes)}</dd>
              </div>
            )}
          </dl>
        )}
      </Page>
      <Page>
        <h2 className={SMALL_HEADING}>Contents</h2>
        <ol className="space-y-2">
          {book.chapters.map((item, i) => (
            <li key={item.recipe?.id ?? item.stub?.id} className="flex gap-3 text-base text-ink">
              <span aria-hidden="true" className="w-6 shrink-0 font-serif text-ink-soft tabular-nums">
                {i + 1}
              </span>
              <Link
                to={`?page=${firstChapterLeaf + i}`}
                className="min-w-0 break-words underline decoration-rule underline-offset-4 transition hover:decoration-ink print:no-underline"
              >
                {chapterTitle(item)}
              </Link>
            </li>
          ))}
        </ol>
      </Page>
    </Sheet>
  );
}

export function ChapterLeaf({ recipe, number }: { recipe: RecipeDisplay; number: number }) {
  return (
    <Sheet>
      <div className="relative aspect-[4/3] bg-tint md:aspect-auto md:min-h-full print:aspect-[16/9] print:max-h-72">
        {recipe.image ? (
          <img src={recipe.image} alt="" className="absolute inset-0 size-full object-cover" />
        ) : (
          <div className="flex size-full items-center justify-center p-8 md:absolute md:inset-0">
            <p className="text-center font-serif text-4xl leading-tight font-medium text-balance break-words text-ink-soft italic">
              {recipe.title}
            </p>
          </div>
        )}
      </div>
      <Page>
        <p className={LABEL}>Chapter {number}</p>
        <h2
          data-leaf-heading
          tabIndex={-1}
          className={`mt-2 font-serif text-3xl leading-tight font-medium text-balance break-words text-ink ${FOCUS_HEADING}`}
        >
          {recipe.title}
        </h2>
        <div className="mt-2">
          <RecipeCredit recipe={recipe} />
        </div>

        <h3 className={`${SMALL_HEADING} mt-6`}>Ingredients</h3>
        <ul className="space-y-1.5">
          {recipe.ingredients.map((ingredient, i) => {
            const line = ingredientLine(ingredient.ingredientName, ingredient.originalText);
            return (
              <li key={i} className="text-base leading-relaxed text-ink">
                {line.name && <><strong className="font-semibold">{line.name}</strong>, </>}
                {line.text}
              </li>
            );
          })}
        </ul>

        <h3 className={`${SMALL_HEADING} mt-6`}>Method</h3>
        <RecipeInstructions recipe={recipe} />

        <p className="mt-6 print:hidden">
          <Link
            to={`/recipes/${recipe.id}`}
            className="text-[15px] font-semibold text-ink underline decoration-rule underline-offset-4 transition hover:decoration-ink"
          >
            Open the recipe page
          </Link>
        </p>
      </Page>
    </Sheet>
  );
}

const STUB_NOTES: Record<HiddenState, string> = {
  under_review: 'This recipe is being reviewed. It comes back here once it is approved.',
  unavailable: 'This recipe is no longer available.',
};

export function StubChapterLeaf({
  title,
  state,
  number,
}: {
  title: string;
  state: HiddenState;
  number: number;
}) {
  return (
    <div className="rounded-card border border-rule bg-surface p-6 sm:p-8 print:rounded-none print:border-0">
      <p className={LABEL}>Chapter {number}</p>
      <h2
        data-leaf-heading
        tabIndex={-1}
        className={`mt-2 font-serif text-3xl leading-tight font-medium text-balance break-words text-ink ${FOCUS_HEADING}`}
      >
        {title}
      </h2>
      <p className="mt-3 text-base text-ink-soft">{STUB_NOTES[state]}</p>
    </div>
  );
}
