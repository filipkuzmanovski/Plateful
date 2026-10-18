package mk.ukim.finki.wp.recipeappbackend.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Runs the admin queue queries against the real Postgres. Opt-in: skipped unless SUPABASE_DB_PASSWORD
 * is set. Writes only inside the rolled-back test transaction; Flyway is disabled so a test can never
 * migrate the real database (V6 must already be applied by the app).
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class AdminQueueDbTest {

    // Far in the past, so the seeded rows sort ahead of any genuinely pending recipe.
    private static final OffsetDateTime EARLY = OffsetDateTime.of(1971, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime LATER = EARLY.plusDays(1);

    @Autowired
    private RecipeRepository recipeRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager em;

    private User author;

    @BeforeEach
    void seedAuthor() {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO auth.users (id) VALUES (:id)").setParameter("id", id).executeUpdate();
        author = userRepository.saveAndFlush(User.builder()
                .id(id).firstName("Queue").lastName("Author").email("queue-" + id + "@example.test").build());
    }

    private Recipe recipe(String title, RecipeStatus status, OffsetDateTime submittedAt) {
        return recipeRepository.saveAndFlush(Recipe.builder()
                .title(title).user(author).status(status).submittedAt(submittedAt).build());
    }

    @Test
    void queueOldestFirstWithIdTieBreak() {
        Recipe tieA = recipe("tie a", RecipeStatus.PENDING, EARLY);
        Recipe tieB = recipe("tie b", RecipeStatus.PENDING, EARLY);
        Recipe newer = recipe("newer", RecipeStatus.PENDING, LATER);
        recipe("decided", RecipeStatus.APPROVED, EARLY.minusDays(1)); // never in the queue
        em.clear();

        List<UUID> tied = List.of(tieA.getId(), tieB.getId()).stream()
                // Postgres orders uuid bytewise, which is the order of the lowercase hex text.
                .sorted(Comparator.comparing(UUID::toString))
                .toList();

        List<UUID> firstThree = recipeRepository
                .findByStatusOrderBySubmittedAtAscIdAsc(RecipeStatus.PENDING, PageRequest.of(0, 3))
                .getContent().stream().map(Recipe::getId).toList();

        assertThat(firstThree).containsExactly(tied.get(0), tied.get(1), newer.getId());
    }

    @Test
    void countByStatusCountsOnlyThatStatus() {
        long before = recipeRepository.countByStatus(RecipeStatus.PENDING);
        recipe("pending one", RecipeStatus.PENDING, EARLY);
        recipe("pending two", RecipeStatus.PENDING, EARLY);
        recipe("rejected", RecipeStatus.REJECTED, EARLY);

        assertThat(recipeRepository.countByStatus(RecipeStatus.PENDING)).isEqualTo(before + 2);
    }

    @Test
    void lockedFetchReturnsRow() {
        Recipe saved = recipe("locked", RecipeStatus.PENDING, EARLY);
        em.clear();

        Recipe found = recipeRepository.findForReviewById(saved.getId()).orElseThrow();

        assertThat(found.getTitle()).isEqualTo("locked");
        assertThat(em.getLockMode(found)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(recipeRepository.findForReviewById(UUID.randomUUID())).isEmpty();
    }
}
