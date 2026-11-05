package mk.ukim.finki.wp.recipeappbackend.model.projection;

import java.util.UUID;

/** One row of the batched recipe count query in CookbookRecipeRepository. */
public record CookbookRecipeCount(
        UUID cookbookId,
        Long recipeCount
) {
}
