package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.RecipeFilter;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeSort;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeComment;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeRating;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.specification.RecipeSpecifications;
import org.junit.jupiter.api.Assumptions;
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

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Runs every browse sort order against the real Postgres, because the ORDER BY is built from
 * correlated subqueries that only the real Hibernate + Postgres pair can vouch for. Opt-in: skipped
 * unless SUPABASE_DB_PASSWORD is set. Read-only; Flyway is disabled so a test can never migrate the
 * real database. The assertions hold for any data, including an empty or one-recipe table.
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class RecipeSortDbTest {

    @Autowired
    private RecipeRepository recipeRepository;
    @Autowired
    private RecipeRatingRepository ratingRepository;
    @Autowired
    private RecipeCommentRepository commentRepository;

    @Autowired
    private UserRepository userRepository;

    private static final RecipeFilter EMPTY =
            new RecipeFilter(null, null, null, null, null, null, null, null, null);

    private Page<Recipe> page(RecipeSort sort, int page, int size) {
        return recipeRepository.findAll(
                RecipeSpecifications.withFilter(EMPTY, Optional.empty())
                        .and(RecipeSpecifications.withSort(sort)),
                PageRequest.of(page, size));
    }

    @Test
    void mostRatedIsNonIncreasingByRatingCount() {
        List<Long> counts = page(RecipeSort.MOST_RATED, 0, 50).getContent().stream()
                .map(r -> ratingRepository.countByRecipeId(r.getId())).toList();

        assertThat(counts).isSortedAccordingTo((a, b) -> Long.compare(b, a));
    }

    @Test
    void topRatedPutsAveragesFirstAndNonIncreasing() {
        List<Double> averages = page(RecipeSort.TOP_RATED, 0, 50).getContent().stream()
                .map(r -> ratingRepository.findAverageRatingByRecipeId(r.getId())).toList();

        int firstNull = averages.indexOf(null);
        if (firstNull >= 0) {
            assertThat(averages.subList(firstNull, averages.size())).containsOnlyNulls();
        }
        List<Double> rated = averages.stream().filter(a -> a != null).toList();
        assertThat(rated).isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }

    @Test
    void mostCommentsIsNonIncreasingByCommentCount() {
        List<Long> counts = page(RecipeSort.MOST_COMMENTS, 0, 50).getContent().stream()
                .map(r -> commentRepository.countByRecipeId(r.getId())).toList();

        assertThat(counts).isSortedAccordingTo((a, b) -> Long.compare(b, a));
    }

    @Test
    void newestIsNonIncreasingByCreatedAt() {
        List<Recipe> recipes = page(RecipeSort.NEWEST, 0, 50).getContent();

        assertThat(recipes).extracting(Recipe::getCreatedAt)
                .isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    void pagesDoNotOverlapAndTotalMatchesTable() {
        Page<Recipe> first = page(RecipeSort.MOST_RATED, 0, 20);
        Page<Recipe> second = page(RecipeSort.MOST_RATED, 1, 20);

        Set<UUID> firstIds = first.getContent().stream().map(Recipe::getId).collect(Collectors.toSet());
        Set<UUID> secondIds = new HashSet<>(second.getContent().stream().map(Recipe::getId).toList());

        assertThat(java.util.Collections.disjoint(firstIds, secondIds)).isTrue();
        assertThat(first.getTotalElements()).isEqualTo(recipeRepository.count());
    }

    /**
     * Seeds four recipes with known rating/comment shapes inside the test transaction (rolled back by
     * @DataJpaTest, never committed) and checks the exact order each sort produces, so the ORDER BY is
     * verified even when the real table is empty or tiny. Borrows two existing users as authors.
     */
    @Test
    void seededRecipesComeBackInTheSpecifiedOrder() throws InterruptedException {
        List<User> users = userRepository.findAll(PageRequest.of(0, 2)).getContent();
        Assumptions.assumeTrue(users.size() >= 2, "Needs two existing users to author ratings");

        Recipe r1 = seed("sort-r1");   // 2 ratings (3, 3): count 2, avg 3, no comments
        Recipe r2 = seed("sort-r2");   // 1 rating (5): count 1, avg 5, 1 comment
        Recipe r3 = seed("sort-r3");   // 1 rating (4): count 1, avg 4, 3 comments
        Recipe r4 = seed("sort-r4");   // no ratings: count 0, avg null, 2 comments
        rate(r1, users.get(0), 3);
        rate(r1, users.get(1), 3);
        rate(r2, users.get(0), 5);
        rate(r3, users.get(0), 4);
        comment(r2, users.get(0), 1);
        comment(r3, users.get(0), 3);
        comment(r4, users.get(0), 2);
        Set<UUID> ids = Set.of(r1.getId(), r2.getId(), r3.getId(), r4.getId());

        assertThat(order(RecipeSort.NEWEST, ids)).containsExactly(r4.getId(), r3.getId(), r2.getId(), r1.getId());
        assertThat(order(RecipeSort.MOST_RATED, ids)).containsExactly(r1.getId(), r2.getId(), r3.getId(), r4.getId());
        assertThat(order(RecipeSort.TOP_RATED, ids)).containsExactly(r2.getId(), r3.getId(), r1.getId(), r4.getId());
        assertThat(order(RecipeSort.MOST_COMMENTS, ids)).containsExactly(r3.getId(), r4.getId(), r2.getId(), r1.getId());
    }

    private List<UUID> order(RecipeSort sort, Set<UUID> ids) {
        Specification<Recipe> onlySeeded = (root, query, cb) -> root.get("id").in(ids);
        return recipeRepository.findAll(
                onlySeeded.and(RecipeSpecifications.withSort(sort)), PageRequest.of(0, 50))
                .getContent().stream().map(Recipe::getId).toList();
    }

    private Recipe seed(String title) throws InterruptedException {
        Thread.sleep(5); // distinct created_at so NEWEST has a strict order
        return recipeRepository.saveAndFlush(Recipe.builder().title(title).build());
    }

    private void rate(Recipe recipe, User user, int stars) {
        ratingRepository.saveAndFlush(RecipeRating.builder().recipe(recipe).user(user).rating(stars).build());
    }

    private void comment(Recipe recipe, User user, int howMany) {
        for (int i = 0; i < howMany; i++) {
            commentRepository.saveAndFlush(RecipeComment.builder().recipe(recipe).user(user).body("c" + i).build());
        }
    }
}
