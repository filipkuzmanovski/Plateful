package mk.ukim.finki.wp.recipeappbackend.nutrition;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.NutritionEstimateDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.GeneratedRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeIngredient;
import mk.ukim.finki.wp.recipeappbackend.repository.NutritionEstimateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** What the API shows for a recipe's estimate, and the edit rule (spec sections 6 and 7). */
@Component
public class NutritionEstimates {

    /** An AI recipe that is not saved is estimated while it is younger than this. */
    public static final Duration RECENT = Duration.ofHours(24);

    private final NutritionEstimateRepository repository;
    private final Clock clock;

    @Autowired
    public NutritionEstimates(NutritionEstimateRepository repository) {
        this(repository, Clock.systemUTC());
    }

    NutritionEstimates(NutritionEstimateRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Not approved wins over everything; imported recipes are never estimated. */
    public NutritionEstimateDto forRecipe(Recipe recipe) {
        if (!recipe.isLive() || !"user".equals(recipe.getSource())
                || recipe.getServings() == null || recipe.getServings() < 1) {
            return NutritionEstimateDto.none();
        }
        Optional<StoredEstimate> stored = repository.forRecipe(recipe.getId());
        if (stored.isEmpty()) {
            return NutritionEstimateDto.pending();
        }
        return fromRow(stored.get(), recipe.getServings());
    }

    public NutritionEstimateDto forGenerated(GeneratedRecipe row, int servings) {
        Optional<StoredEstimate> stored = repository.forGenerated(row.getId());
        if (stored.isPresent()) {
            return servings < 1 ? NutritionEstimateDto.none() : fromRow(stored.get(), servings);
        }
        boolean eligible = row.getSavedAt() != null
                || row.getCreatedAt().isAfter(OffsetDateTime.now(clock).minus(RECENT));
        return eligible ? NutritionEstimateDto.pending() : NutritionEstimateDto.none();
    }

    /** Deletes the stored row, a failed one too, when the recipe's ingredients no longer match its hash. */
    public void removeIfIngredientsChanged(UUID recipeId, List<RecipeIngredient> ingredients) {
        repository.deleteForRecipeIfHashDiffers(recipeId, NutritionLines.hash(NutritionLines.forRecipe(ingredients)));
    }

    private static NutritionEstimateDto fromRow(StoredEstimate stored, int servings) {
        return "ready".equals(stored.status()) && stored.calories() != null
                ? NutritionEstimateDto.ready(stored, servings) : NutritionEstimateDto.none();
    }
}
