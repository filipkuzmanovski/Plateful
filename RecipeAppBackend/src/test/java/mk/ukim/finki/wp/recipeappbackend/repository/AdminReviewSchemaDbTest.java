package mk.ukim.finki.wp.recipeappbackend.repository;

import jakarta.persistence.EntityManager;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Checks the admin-review columns (V6) against the real Postgres. Opt-in: skipped unless
 * SUPABASE_DB_PASSWORD is set. Writes only inside the rolled-back test transaction; Flyway is
 * disabled so a test can never migrate the real database (V6 must already be applied by the app).
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class AdminReviewSchemaDbTest {

    @Autowired
    private RecipeRepository recipeRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager em;

    // users.id references auth.users(id), so a throwaway user needs a row in both; the enclosing
    // test transaction rolls both back.
    private UUID newUser(String name) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO auth.users (id) VALUES (:id)").setParameter("id", id).executeUpdate();
        userRepository.saveAndFlush(User.builder()
                .id(id).firstName(name).lastName("Reviewer").email(name + "-" + id + "@example.test").build());
        return id;
    }

    // Only rows that existed when V6 ran: recipes created later may legitimately be pending or rejected.
    private long countMigratedRecipes(String where) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM recipes WHERE (" + where + ") "
                        + "AND created_at <= (SELECT installed_on FROM flyway_schema_history WHERE version = '6')")
                .getSingleResult()).longValue();
    }

    @Test
    void existingRecipesAreApproved() {
        assertThat(countMigratedRecipes("status <> 'approved'")).isZero();
        assertThat(countMigratedRecipes("approved_title IS DISTINCT FROM title")).isZero();
        assertThat(countMigratedRecipes("NOT ever_approved")).isZero();
    }

    @Test
    void newRecipeDefaultsToPending() {
        UUID saved = recipeRepository.saveAndFlush(Recipe.builder().title("review-default").build()).getId();
        em.clear();

        Recipe read = recipeRepository.findById(saved).orElseThrow();

        assertThat(read.getStatus()).isEqualTo(RecipeStatus.PENDING);
        assertThat(read.getRevision()).isZero();
        assertThat(read.isEverApproved()).isFalse();
        assertThat(read.getSubmittedAt()).isNotNull();
    }

    @Test
    void isAdminDefaultsToFalse() {
        UUID id = newUser("plain");
        em.clear();

        assertThat(userRepository.findById(id).orElseThrow().isAdmin()).isFalse();
    }

    @Test
    void countByUserIdAndStatus() {
        User owner = userRepository.findById(newUser("owner")).orElseThrow();
        recipeRepository.saveAndFlush(Recipe.builder().title("counted-rejected").user(owner)
                .status(RecipeStatus.REJECTED).rejectionReason("Needs more detail").build());
        recipeRepository.saveAndFlush(Recipe.builder().title("counted-approved").user(owner)
                .status(RecipeStatus.APPROVED).everApproved(true).build());
        em.clear();

        assertThat(recipeRepository.countByUserIdAndStatus(owner.getId(), RecipeStatus.REJECTED)).isEqualTo(1L);
        assertThat(recipeRepository.countByUserIdAndStatus(owner.getId(), RecipeStatus.APPROVED)).isEqualTo(1L);
        assertThat(recipeRepository.countByUserIdAndStatus(owner.getId(), RecipeStatus.PENDING)).isZero();
    }
}
