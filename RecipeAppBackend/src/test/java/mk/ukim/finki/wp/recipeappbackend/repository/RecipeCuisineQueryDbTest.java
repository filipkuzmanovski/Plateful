package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.RecipeFilter;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.projection.CuisineCount;
import mk.ukim.finki.wp.recipeappbackend.specification.RecipeSpecifications;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Runs the real cuisine queries against the real Postgres, because faked repositories once hid a
 * SQL type error that surfaced as a 500 in production. Opt-in: skipped unless SUPABASE_DB_PASSWORD
 * is set. Read-only; Flyway is disabled so a test can never migrate the real database.
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class RecipeCuisineQueryDbTest {

    @Autowired
    private RecipeRepository recipeRepository;

    private static RecipeFilter filter(Boolean vegetarian) {
        return new RecipeFilter(null, null, null, null, null, null, vegetarian, null, null, null);
    }

    @Test
    void anyOfFilterReturnsOnlyMatchingRecipes() {
        List<Recipe> result = recipeRepository.findAll(
                RecipeSpecifications.withFilter(filter(null), Optional.of(Set.of("italian", "greek"))));

        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(r ->
                assertThat(r.getCuisines()).containsAnyOf("italian", "greek"));
    }

    @Test
    void anyOfFilterCombinesWithDietFlag() {
        List<Recipe> result = recipeRepository.findAll(
                RecipeSpecifications.withFilter(filter(true), Optional.of(Set.of("italian"))));

        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(r -> {
            assertThat(r.isVegetarian()).isTrue();
            assertThat(r.getCuisines()).contains("italian");
        });
    }

    @Test
    void countCuisinesReturnsPositiveCounts() {
        List<CuisineCount> counts = recipeRepository.countCuisines();

        assertThat(counts).anySatisfy(c -> {
            assertThat(c.getKey()).isEqualTo("italian");
            assertThat(c.getCount()).isPositive();
        });
    }
}
