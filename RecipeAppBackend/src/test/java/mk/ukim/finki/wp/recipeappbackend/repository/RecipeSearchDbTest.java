package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.RecipeFilter;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeSort;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeIngredient;
import mk.ukim.finki.wp.recipeappbackend.specification.RecipeSpecifications;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Runs the recipe text search (title or ingredient name) against the real Postgres, because the
 * EXISTS subquery and the LIKE escaping can only be vouched for by the real Hibernate + Postgres
 * pair. Opt-in: skipped unless SUPABASE_DB_PASSWORD is set. Writes only inside @DataJpaTest's
 * rolled-back test transaction, never committed; Flyway is disabled so a test can never migrate the
 * real database. Every assertion is restricted to the seeded ids, because the live table holds other
 * recipes too.
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class RecipeSearchDbTest {

    @Autowired
    private RecipeRepository recipeRepository;
    @Autowired
    private RecipeIngredientRepository ingredientRepository;

    private Recipe shrimp;   // title "Garlic Butter Shrimp", ingredients prawns + butter, not vegan
    private Recipe soup;     // title "Tomato Soup", ingredients tomato + two garlic lines, vegan
    private Recipe juice;    // title "100% Orange Juice", ingredient orange, vegan
    private Recipe stew;     // title "Chicken Stew", ingredients chicken + carrot, not vegan
    private Set<UUID> seededIds;

    @BeforeEach
    void seed() {
        shrimp = recipe("Garlic Butter Shrimp", false, "prawns", "butter");
        soup = recipe("Tomato Soup", true, "tomato", "fresh garlic cloves", "Garlic powder");
        juice = recipe("100% Orange Juice", true, "orange");
        stew = recipe("Chicken Stew", false, "chicken", "carrot");
        seededIds = Set.of(shrimp.getId(), soup.getId(), juice.getId(), stew.getId());
    }

    private Recipe recipe(String title, boolean vegan, String... ingredients) {
        Recipe saved = recipeRepository.saveAndFlush(Recipe.builder().title(title).vegan(vegan).build());
        int order = 0;
        for (String name : ingredients) {
            ingredientRepository.saveAndFlush(RecipeIngredient.builder()
                    .recipe(saved).ingredientName(name).originalText("1 " + name).sortOrder(order++).build());
        }
        return saved;
    }

    private static RecipeFilter search(String q, Boolean vegan) {
        return new RecipeFilter(null, null, null, null, null, vegan, null, null, null, q);
    }

    private Specification<Recipe> onlySeeded() {
        return (root, query, cb) -> root.get("id").in(seededIds);
    }

    private Page<Recipe> run(RecipeFilter filter, RecipeSort sort, int page, int size) {
        return recipeRepository.findAll(
                onlySeeded()
                        .and(RecipeSpecifications.withFilter(filter, Optional.empty()))
                        .and(RecipeSpecifications.withSort(sort)),
                PageRequest.of(page, size));
    }

    private List<UUID> ids(String q) {
        return run(search(q, null), RecipeSort.NEWEST, 0, 50).getContent().stream().map(Recipe::getId).toList();
    }

    @Test
    void matchesATitleWord() {
        assertThat(ids("shrimp")).containsExactly(shrimp.getId());
    }

    @Test
    void matchesAnIngredientOnlyWord() {
        assertThat(ids("carrot")).containsExactly(stew.getId());
    }

    @Test
    void multiWordQueryMayBeSplitBetweenTitleAndIngredient() {
        // "chicken" is in the title, "carrot" only in an ingredient
        assertThat(ids("chicken carrot")).containsExactly(stew.getId());
        // every word must match somewhere: "tomato" is not in the stew at all
        assertThat(ids("chicken tomato")).isEmpty();
    }

    @Test
    void capitalsAndSurroundingSpacesAreIgnored() {
        assertThat(ids("  GARLIC ")).containsExactlyInAnyOrder(shrimp.getId(), soup.getId());
    }

    @Test
    void percentMatchesOnlyALiteralPercent() {
        assertThat(ids("%")).containsExactly(juice.getId());
    }

    @Test
    void underscoreIsNotAWildcard() {
        assertThat(ids("_")).isEmpty();
    }

    @Test
    void searchStillAndsWithADietFlag() {
        List<UUID> vegan = run(search("garlic", true), RecipeSort.NEWEST, 0, 50)
                .getContent().stream().map(Recipe::getId).toList();

        assertThat(vegan).containsExactly(soup.getId());
    }

    @Test
    void searchRunsTogetherWithASort() {
        Page<Recipe> result = run(search("garlic", null), RecipeSort.MOST_RATED, 0, 50);

        assertThat(result.getContent()).extracting(Recipe::getId)
                .containsExactlyInAnyOrder(shrimp.getId(), soup.getId());
    }

    @Test
    void twoMatchingIngredientsDoNotDuplicateTheRecipe() {
        // the soup has two garlic ingredient lines; the page must still list it once
        Page<Recipe> result = run(search("garlic", null), RecipeSort.NEWEST, 0, 50);

        assertThat(result.getContent()).extracting(Recipe::getId)
                .containsExactlyInAnyOrder(shrimp.getId(), soup.getId());
        assertThat(result.getTotalElements()).isEqualTo(2);
        Page<Recipe> firstOfOne = run(search("garlic", null), RecipeSort.NEWEST, 0, 1);
        assertThat(firstOfOne.getContent()).hasSize(1);
        assertThat(firstOfOne.getTotalElements()).isEqualTo(2);
    }

    @Test
    void blankQueryDoesNotFilter() {
        assertThat(ids("   ")).containsExactlyInAnyOrder(
                shrimp.getId(), soup.getId(), juice.getId(), stew.getId());
    }
}
