package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeIngredient;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeIngredientRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One recipe + its ingredients in ONE transaction (same rule as
 * RecipeService.create: a crash halfway can't leave an ingredient-less
 * recipe). Separate class rather than a method on the import service
 * because @Transactional doesn't apply to self-invocation.
 */
@Component
public class SpoonacularRecipePersister {

    private final RecipeRepository recipeRepository;
    private final RecipeIngredientRepository ingredientRepository;

    public SpoonacularRecipePersister(RecipeRepository recipeRepository,
                                      RecipeIngredientRepository ingredientRepository) {
        this.recipeRepository = recipeRepository;
        this.ingredientRepository = ingredientRepository;
    }

    @Transactional
    public void persist(Recipe recipe, java.util.List<RecipeIngredient> ingredients) {
        recipeRepository.save(recipe);
        ingredientRepository.saveAll(ingredients);
    }
}
