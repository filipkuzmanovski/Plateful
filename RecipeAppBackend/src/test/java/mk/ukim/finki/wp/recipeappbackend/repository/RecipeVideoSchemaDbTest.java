package mk.ukim.finki.wp.recipeappbackend.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Checks the video columns (V7) against the real Postgres. Opt-in: skipped unless
 * SUPABASE_DB_PASSWORD is set. Writes only inside the rolled-back test transaction; Flyway is
 * disabled so a test can never migrate the real database (V7 must already be applied by the app).
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class RecipeVideoSchemaDbTest {

    @Autowired
    private RecipeRepository recipeRepository;
    @Autowired
    private EntityManager em;

    // Only rows that existed when V7 ran: recipes created later may legitimately have a video.
    private long countMigratedRecipes(String where) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM recipes WHERE (" + where + ") "
                        + "AND created_at <= (SELECT installed_on FROM flyway_schema_history WHERE version = '7')")
                .getSingleResult()).longValue();
    }

    @Test
    void existingRecipesHaveNoVideo() {
        // guards against a vacuous pass: the scope must contain the pre-V7 recipes (and a V7 row must exist)
        assertThat(countMigratedRecipes("TRUE")).isPositive();
        assertThat(countMigratedRecipes("video_path IS NOT NULL OR video_poster_path IS NOT NULL "
                + "OR video_duration_seconds IS NOT NULL")).isZero();
    }

    @Test
    void partialVideoViolatesConstraint() {
        UUID id = recipeRepository.saveAndFlush(Recipe.builder().title("partial-video").build()).getId();
        em.clear();

        assertThatThrownBy(() -> {
            em.createNativeQuery("UPDATE recipes SET video_path = :p WHERE id = :id")
                    .setParameter("p", "u/x.mp4").setParameter("id", id).executeUpdate();
            em.flush();
        }).isInstanceOfAny(DataIntegrityViolationException.class, PersistenceException.class);
    }

    @Test
    void completeVideoRoundTrips() {
        Recipe r = Recipe.builder().title("full-video").build();
        r.setVideoPath("u/x.mp4");
        r.setVideoPosterPath("u/x.jpg");
        r.setVideoDurationSeconds(42);
        UUID id = recipeRepository.saveAndFlush(r).getId();
        em.clear();

        Recipe read = recipeRepository.findById(id).orElseThrow();

        assertThat(read.getVideoPath()).isEqualTo("u/x.mp4");
        assertThat(read.getVideoPosterPath()).isEqualTo("u/x.jpg");
        assertThat(read.getVideoDurationSeconds()).isEqualTo(42);
    }
}
