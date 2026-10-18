package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;



import mk.ukim.finki.wp.recipeappbackend.model.InstructionStep;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * user is nullable — imported recipes have no owning user
 * (recipes.user_id is nullable exactly for this case).
 * averageRating/ratingCount/commentCount aren't columns on recipes — they're
 * aggregates from recipe_ratings/recipe_comments, computed by the service
 * layer and passed in here rather than derived from the entity alone.
 * ingredients IS embedded directly (unlike ratings/comments) since a recipe
 * is essentially never shown without its ingredient list, and the count per
 * recipe is always small — no benefit to a separate paginated fetch the way
 * ratings/comments (potentially hundreds per recipe) get.
 * spoonacular_id is intentionally left out — it's an internal import key,
 * nothing a client needs to see.
 */
public record RecipeDisplayDto(
        UUID id, //id of the recipe so that we can send it to the frontend to fetch comments and stuff
        String source,
        UserDisplayDto user,
        String title,
        String image,
        // Public URLs in position order; position 0 is the cover.
        List<String> photos,
        Integer servings,
        Integer readyInMinutes,
        Integer cookingMinutes,
        Integer preparationMinutes,
        String sourceName,
        String sourceUrl,
        String instructions,
        // BUGFIX (review issue #1): was List<String>; the entity now stores
        // structured InstructionStep objects, so the display DTO mirrors it.
        List<InstructionStep> instructionsSteps,
        boolean dairyFree,
        boolean glutenFree,
        boolean vegan,
        boolean vegetarian,
        List<String> cuisines,
        BigDecimal calories,
        BigDecimal proteinGrams,
        BigDecimal fatGrams,
        BigDecimal carbsGrams,
        Map<String, Object> nutrition,
        List<RecipeIngredientDisplayDto> ingredients,
        Double averageRating,
        long ratingCount,
        long commentCount,
        OffsetDateTime createdAt,
        // Null when the recipe has no video.
        RecipeVideoDto video,
        // Present only when the viewer is the author or an admin; null for everyone else.
        RecipeReviewDto review
) {
    public static RecipeDisplayDto fromEntity(
            Recipe recipe,
            List<RecipeIngredientDisplayDto> ingredients,
            List<String> photos,
            Double averageRating,
            long ratingCount,
            long commentCount,
            RecipeVideoDto video
    ) {
        return new RecipeDisplayDto(
                recipe.getId(),
                recipe.getSource(),
                recipe.getUser() != null ? UserDisplayDto.fromEntity(recipe.getUser()) : null, //can be null because it may be an imported recipe
                recipe.getTitle(),
                recipe.getImage(),
                photos,
                recipe.getServings(),
                recipe.getReadyInMinutes(),
                recipe.getCookingMinutes(),
                recipe.getPreparationMinutes(),
                recipe.getSourceName(),
                recipe.getSourceUrl(),
                recipe.getInstructions(),
                recipe.getInstructionsSteps(),
                recipe.isDairyFree(),
                recipe.isGlutenFree(),
                recipe.isVegan(),
                recipe.isVegetarian(),
                recipe.getCuisines(),
                recipe.getCalories(),
                recipe.getProteinGrams(),
                recipe.getFatGrams(),
                recipe.getCarbsGrams(),
                recipe.getNutrition(),
                ingredients,
                averageRating,
                ratingCount,
                commentCount,
                recipe.getCreatedAt(),
                video,
                null
        );
    }

    public RecipeDisplayDto withReview(RecipeReviewDto review) {
        return new RecipeDisplayDto(id, source, user, title, image, photos, servings, readyInMinutes,
                cookingMinutes, preparationMinutes, sourceName, sourceUrl, instructions, instructionsSteps,
                dairyFree, glutenFree, vegan, vegetarian, cuisines, calories, proteinGrams, fatGrams,
                carbsGrams, nutrition, ingredients, averageRating, ratingCount, commentCount, createdAt, video, review);
    }
}
