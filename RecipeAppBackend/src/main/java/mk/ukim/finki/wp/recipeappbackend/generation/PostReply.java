package mk.ukim.finki.wp.recipeappbackend.generation;

/**
 * The AI's reply to a post-recipe writing request, read by {@link PostReplyParser}. Exactly one of
 * {@code noDish}, {@code draft} and {@code problem} is meaningful: {@code noDish} when the AI said the post
 * shows no dish, {@code draft} (with {@code post}) for a recipe, {@code problem} when the reply was unusable.
 *
 * @param noDish  true when the AI set {@code origin} to {@code none}
 * @param draft   the structurally checked recipe; null unless the origin was {@code recipe} or {@code dish}
 * @param post    the origin and the ingredient swaps; set together with {@code draft}, otherwise null
 * @param problem why the reply was rejected, fit for the retry prompt; null unless the reply was unusable
 */
public record PostReply(boolean noDish, RecipeDraft draft, StoredRecipe.PostNote post, DraftProblem problem) {
}
