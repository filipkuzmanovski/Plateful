package mk.ukim.finki.wp.recipeappbackend.repository;

import jakarta.persistence.EntityManager;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Checks the {@code kind} check constraint of generated_recipes against the real Postgres. Opt-in:
 * skipped unless SUPABASE_DB_PASSWORD is set. The post kind needs V11, which the backend applies on its
 * next start (Flyway is disabled here so a test can never migrate the real database), so that test skips
 * until then. Writes stay in the rolled-back test transaction.
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class GeneratedRecipeKindDbTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    private boolean constraintAllowsPost() {
        String definition = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'public.generated_recipes'::regclass AND contype = 'c'
                  AND conname = 'generated_recipes_kind_check'
                """, String.class);
        return definition != null && definition.contains("'post'");
    }

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO auth.users (id) VALUES (:id)").setParameter("id", id).executeUpdate();
        userRepository.saveAndFlush(User.builder()
                .id(id).firstName("Kind").lastName("Check").email("kind-" + id + "@example.test").build());
        return id;
    }

    private int insert(UUID userId, String kind) {
        return jdbc.update("""
                INSERT INTO generated_recipes (user_id, kind, title, recipe, request, provider, model, novelty_score)
                VALUES (?, ?, 'Kind check', '{}'::jsonb, '{}'::jsonb, 'test', 'test-model', 0)
                """, userId, kind);
    }

    @Test
    void postKind_inserts() {
        assumeTrue(constraintAllowsPost(), "V11 is not applied yet (restart the backend)");

        assertThat(insert(newUser(), "post")).isEqualTo(1);
    }

    @Test
    void unknownKind_isRefused() {
        UUID userId = newUser();

        assertThatThrownBy(() -> insert(userId, "other")).isInstanceOf(DataIntegrityViolationException.class);
    }
}
