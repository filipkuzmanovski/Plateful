package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replace-mode support: removes every previously imported recipe in one
 * transaction. Separate class because @Transactional doesn't apply to
 * self-invocation (same reason as SpoonacularRecipePersister).
 */
@Component
public class SpoonacularImportCleaner {

    private final RecipeRepository recipeRepository;

    public SpoonacularImportCleaner(RecipeRepository recipeRepository) {
        this.recipeRepository = recipeRepository;
    }

    @Transactional
    public int deleteAllImported() {
        return recipeRepository.deleteAllImported();
    }
}
