package mk.ukim.finki.wp.recipeappbackend.repository;

import jakarta.persistence.EntityManager;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipePhoto;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Runs the recipe_photos queries against the real Postgres, including the UNIQUE (recipe_id, position)
 * constraint that "replace the photo list" must not violate. Opt-in: skipped unless
 * SUPABASE_DB_PASSWORD is set. Rows are written only inside the transaction that @DataJpaTest opens
 * and rolls back after each test, so nothing is ever committed. Flyway is disabled so a test can never
 * migrate the real database; V3 must already be applied.
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class RecipePhotoRepositoryDbTest {

    @Autowired
    private RecipePhotoRepository recipePhotoRepository;

    @Autowired
    private RecipeRepository recipeRepository;

    @Autowired
    private EntityManager entityManager;

    private RecipePhoto photo(Recipe recipe, String path, int position) {
        return recipePhotoRepository.save(
                RecipePhoto.builder().recipe(recipe).path(path).position(position).build());
    }

    @Test
    void replaceKeepsPositionsUniqueAndOrdered() {
        // Imported recipes may already carry photo rows, which would clash with positions 0..2 on
        // UNIQUE (recipe_id, position); use one that has none. Everything is rolled back anyway.
        Recipe recipe = recipeRepository.findAll(PageRequest.of(0, 200)).getContent().stream()
                .filter(r -> !recipePhotoRepository.existsByRecipeId(r.getId()))
                .findFirst()
                .orElse(null);
        Assumptions.assumeTrue(recipe != null, "No recipe without photo rows to use as a fixture");
        UUID id = recipe.getId();

        photo(recipe, "a", 0);
        photo(recipe, "b", 1);
        photo(recipe, "c", 2);
        recipePhotoRepository.deleteAllByRecipeId(id);
        photo(recipe, "c", 0);
        photo(recipe, "a", 1);
        entityManager.flush();

        assertThat(recipePhotoRepository.findByRecipeIdOrderByPositionAsc(id))
                .extracting(RecipePhoto::getPath)
                .containsExactly("c", "a");
        assertThat(recipePhotoRepository.existsByRecipeId(id)).isTrue();
    }

    @Test
    void batchedFindGroupsByRecipe() {
        assertThat(recipePhotoRepository.findByRecipeIdInOrderByPositionAsc(List.of(UUID.randomUUID())))
                .isEmpty();
    }
}
