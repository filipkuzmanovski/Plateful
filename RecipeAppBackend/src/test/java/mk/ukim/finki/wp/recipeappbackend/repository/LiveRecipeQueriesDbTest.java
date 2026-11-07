package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.generation.ComparedRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeFilter;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeComment;
import mk.ukim.finki.wp.recipeappbackend.model.projection.CuisineCount;
import mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionTarget;
import mk.ukim.finki.wp.recipeappbackend.specification.RecipeSpecifications;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Spec 3.2 on the real Postgres: an approved recipe that reports have hidden is left out of browse, cuisine
 * counts, an author page seen by a stranger, the novelty comparison and the calorie estimate target, and a
 * hidden comment is left out of the comment list for anyone but its author. Each test has a live control
 * that the same query does return. Opt-in: skipped unless SUPABASE_DB_PASSWORD is set, and skipped until V15
 * adds recipes.hidden_at (the backend applies it on its next start; Flyway is disabled here so a test can
 * never migrate the real database). Fixtures are dated 2099 and carry a random marker; every write stays in
 * the rolled-back test transaction.
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class LiveRecipeQueriesDbTest {

    private static final OffsetDateTime SINCE_2098 = OffsetDateTime.parse("2098-01-01T00:00:00Z");
    private static final OffsetDateTime Y2099_01 = OffsetDateTime.parse("2099-01-01T00:00:00Z");
    private static final OffsetDateTime Y2099_02 = OffsetDateTime.parse("2099-01-02T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RecipeRepository recipeRepository;
    @Autowired
    private RecipeCommentRepository commentRepository;

    private String marker;
    private UUID authorId;
    private UUID strangerId;
    private UUID live;
    private UUID hidden;

    private boolean columnExists(String table, String column) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, Integer.class, table, column);
        return n != null && n > 0;
    }

    @BeforeEach
    void seed() {
        assumeTrue(columnExists("recipes", "hidden_at") && columnExists("recipe_comments", "hidden_at"),
                "V15 is not applied yet (restart the backend)");

        marker = "zzlive" + letters(10) + "q";
        authorId = newUser("Author");
        strangerId = newUser("Stranger");
        live = newRecipe(marker + " live", "zz-" + marker + "-live", Y2099_01, null);
        hidden = newRecipe(marker + " hidden", "zz-" + marker + "-hidden", Y2099_02, Y2099_02);
    }

    // Letters a-p only, so the ingredient-name normaliser cannot singularise the marker.
    private static String letters(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append((char) ('a' + ThreadLocalRandom.current().nextInt(16)));
        }
        return sb.toString();
    }

    // users.id references auth.users(id), so a throwaway user needs a row in both.
    private UUID newUser(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO auth.users (id) VALUES (?)", id);
        jdbc.update("INSERT INTO users (id, first_name, last_name, email) VALUES (?, ?, 'Live', ?)",
                id, name, name + "-" + id + "@example.test");
        return id;
    }

    /** An approved user upload with servings and one ingredient named after the marker. */
    private UUID newRecipe(String title, String cuisineKey, OffsetDateTime reviewedAt, OffsetDateTime hiddenAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO recipes (id, user_id, source, title, approved_title, servings, status, ever_approved,
                                             reviewed_at, created_at, cuisines, hidden_at)
                        VALUES (?, ?, 'user', ?, ?, 4, 'approved', true, ?, ?, CAST(? AS text[]), ?)
                        """,
                id, authorId, title, title, reviewedAt, reviewedAt, "{" + cuisineKey + "}", hiddenAt);
        jdbc.update("INSERT INTO recipe_ingredients (recipe_id, ingredient_name, original_text, sort_order) "
                + "VALUES (?, ?, ?, 0)", id, marker, "1 " + marker);
        return id;
    }

    private static RecipeFilter search(String q) {
        return new RecipeFilter(null, null, null, null, null, null, null, null, null, q);
    }

    @Test
    void hiddenRecipe_isLeftOutOfBrowse() {
        List<Recipe> result = recipeRepository.findAll(RecipeSpecifications.liveOnly()
                .and(RecipeSpecifications.withFilter(search(marker), Optional.empty())));

        assertThat(result).extracting(Recipe::getId).containsExactly(live);
    }

    @Test
    void hiddenRecipe_isLeftOutOfCuisineCounts() {
        assertThat(recipeRepository.countCuisines()).extracting(CuisineCount::getKey)
                .contains("zz-" + marker + "-live")
                .doesNotContain("zz-" + marker + "-hidden");
    }

    @Test
    void hiddenRecipe_isLeftOutOfTheAuthorPageForAStranger() {
        assertThat(recipeRepository.findByUserIdAndStatusAndHiddenAtIsNull(
                        authorId, RecipeStatus.APPROVED, PageRequest.of(0, 10)).getContent())
                .extracting(Recipe::getId)
                .containsExactly(live);
    }

    @Test
    void hiddenRecipe_isLeftOutOfTheNoveltyComparison() {
        PlatefulComparisonRepository plateful = new PlatefulComparisonRepository(new NamedParameterJdbcTemplate(jdbc));

        List<ComparedRecipe> closest = plateful.closestApproved(Set.of(marker), 10);

        assertThat(closest).extracting(ComparedRecipe::title)
                .contains(marker + " live")
                .doesNotContain(marker + " hidden");
    }

    @Test
    void hiddenRecipe_isNeverTheEstimateTarget() {
        Boolean exists = jdbc.queryForObject("SELECT to_regclass('public.nutrition_estimates') IS NOT NULL",
                Boolean.class);
        assumeTrue(Boolean.TRUE.equals(exists), "V13 is not applied yet");
        NutritionEstimateRepository estimates = new NutritionEstimateRepository(new NamedParameterJdbcTemplate(jdbc));

        // the hidden recipe is the newer one, so it would come first if it were not left out
        assertThat(estimates.next(SINCE_2098)).contains(new NutritionTarget(NutritionTarget.Kind.RECIPE, live));
    }

    @Test
    void hiddenComment_isListedForItsAuthorOnly() {
        UUID commenterId = newUser("Commenter");
        UUID shownComment = UUID.randomUUID();
        UUID hiddenComment = UUID.randomUUID();
        jdbc.update("INSERT INTO recipe_comments (id, recipe_id, user_id, body, created_at) VALUES (?, ?, ?, 'Shown', ?)",
                shownComment, live, commenterId, Y2099_01);
        jdbc.update("INSERT INTO recipe_comments (id, recipe_id, user_id, body, created_at, hidden_at) "
                + "VALUES (?, ?, ?, 'Hidden', ?, ?)", hiddenComment, live, commenterId, Y2099_02, Y2099_02);
        PageRequest page = PageRequest.of(0, 10);

        assertThat(commentRepository.findShownByRecipeId(live, strangerId, page).getContent())
                .extracting(RecipeComment::getId).containsExactly(shownComment);
        assertThat(commentRepository.findShownByRecipeId(live, null, page).getContent())
                .extracting(RecipeComment::getId).containsExactly(shownComment);
        assertThat(commentRepository.findShownByRecipeId(live, commenterId, page).getContent())
                .extracting(RecipeComment::getId).containsExactly(hiddenComment, shownComment);
        assertThat(commentRepository.findShownByRecipeId(live, strangerId, page).getTotalElements()).isEqualTo(1);
        // the admin list keeps everything
        assertThat(commentRepository.findByRecipeIdOrderByCreatedAtDesc(live, page).getContent())
                .extracting(RecipeComment::getId).containsExactly(hiddenComment, shownComment);
    }

    @Test
    void lockedCommentRead_andRecipeIdLookup_work() {
        UUID commentId = UUID.randomUUID();
        jdbc.update("INSERT INTO recipe_comments (id, recipe_id, user_id, body) VALUES (?, ?, ?, 'Lock me')",
                commentId, live, strangerId);

        assertThat(commentRepository.findRecipeIdById(commentId)).contains(live);
        assertThat(commentRepository.findForUpdateById(commentId)).map(RecipeComment::getBody).contains("Lock me");
    }
}
