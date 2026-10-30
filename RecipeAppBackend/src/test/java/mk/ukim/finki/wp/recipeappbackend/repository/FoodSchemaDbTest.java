package mk.ukim.finki.wp.recipeappbackend.repository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Checks that the V8 tables exist in the real Postgres. Opt-in: skipped unless SUPABASE_DB_PASSWORD
 * is set. Read-only; Flyway is disabled so a test can never migrate the real database (V8 must
 * already be applied by the app).
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class FoodSchemaDbTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v8TablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
                        + "AND table_name IN ('user_preferences', 'reference_recipes', 'ingredient_stats', 'ingredient_pairings')",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "user_preferences", "reference_recipes", "ingredient_stats", "ingredient_pairings");
    }
}
