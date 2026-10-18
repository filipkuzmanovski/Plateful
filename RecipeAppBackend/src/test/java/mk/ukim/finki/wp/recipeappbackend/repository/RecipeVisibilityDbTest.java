package mk.ukim.finki.wp.recipeappbackend.repository;

import jakarta.persistence.EntityManager;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeFilter;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.model.projection.CuisineCount;
import mk.ukim.finki.wp.recipeappbackend.specification.RecipeSpecifications;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Runs the visibility queries against the real Postgres. Opt-in: skipped unless SUPABASE_DB_PASSWORD
 * is set. Writes only inside the rolled-back test transaction; Flyway is disabled so a test can never
 * migrate the real database (V6 must already be applied by the app).
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class RecipeVisibilityDbTest {

    @Autowired
    private RecipeRepository recipeRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager em;

    private String marker;
    private String cuisineKey;
    private UUID authorId;
    private UUID otherId;
    private UUID adminId;
    private Recipe approved;
    private Recipe pendingNeverApproved;
    private Recipe pendingEverApproved;

    @BeforeEach
    void seed() {
        marker = "vis" + UUID.randomUUID().toString().replace("-", "");
        cuisineKey = "zz-" + marker;
        authorId = newUser("Author");
        otherId = newUser("Other");
        adminId = newUser("Admin");
        em.createNativeQuery("UPDATE users SET is_admin = true WHERE id = :id")
                .setParameter("id", adminId).executeUpdate();

        User author = userRepository.findById(authorId).orElseThrow();
        approved = recipe(author, marker + " approved", RecipeStatus.APPROVED, true, List.of());
        pendingNeverApproved = recipe(author, marker + " pending", RecipeStatus.PENDING, false, List.of(cuisineKey));
        pendingEverApproved = recipe(author, marker + " edited", RecipeStatus.PENDING, true, List.of());
        em.clear();
    }

    // users.id references auth.users(id), so a throwaway user needs a row in both; the enclosing
    // test transaction rolls both back.
    private UUID newUser(String name) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO auth.users (id) VALUES (:id)").setParameter("id", id).executeUpdate();
        userRepository.saveAndFlush(User.builder()
                .id(id).firstName(name).lastName("Visibility").email(name + "-" + id + "@example.test").build());
        return id;
    }

    private Recipe recipe(User author, String title, RecipeStatus status, boolean everApproved, List<String> cuisines) {
        return recipeRepository.saveAndFlush(Recipe.builder()
                .title(title).user(author).status(status).everApproved(everApproved).cuisines(cuisines).build());
    }

    private static RecipeFilter search(String q) {
        return new RecipeFilter(null, null, null, null, null, null, null, null, null, q);
    }

    @Test
    void browseShowsApprovedOnlyForEveryone() {
        List<Recipe> result = recipeRepository.findAll(
                RecipeSpecifications.approvedOnly()
                        .and(RecipeSpecifications.withFilter(search(marker), Optional.empty())));

        assertThat(result).extracting(Recipe::getId).containsExactly(approved.getId());
    }

    @Test
    void cuisineCountsIgnoreUnapproved() {
        List<CuisineCount> counts = recipeRepository.countCuisines();

        assertThat(counts).extracting(CuisineCount::getKey).doesNotContain(cuisineKey);
    }

    @Test
    void profileQueriesSplitByStatus() {
        assertThat(recipeRepository.findByUserId(authorId, PageRequest.of(0, 10)).getContent())
                .extracting(Recipe::getId)
                .containsExactlyInAnyOrder(approved.getId(), pendingNeverApproved.getId(), pendingEverApproved.getId());

        assertThat(recipeRepository.findByUserIdAndStatus(authorId, RecipeStatus.APPROVED, PageRequest.of(0, 10))
                .getContent())
                .extracting(Recipe::getId)
                .containsExactly(approved.getId());
    }

    @Test
    void isAdminReadsFlag() {
        assertThat(userRepository.isAdmin(adminId)).isTrue();
        assertThat(userRepository.isAdmin(otherId)).isFalse();
        assertThat(userRepository.isAdmin(UUID.randomUUID())).isFalse();
    }
}
