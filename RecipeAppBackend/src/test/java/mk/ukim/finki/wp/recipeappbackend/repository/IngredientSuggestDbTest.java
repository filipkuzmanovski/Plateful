package mk.ukim.finki.wp.recipeappbackend.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Runs the real prefix query against the real Postgres. Opt-in: skipped unless SUPABASE_DB_PASSWORD
 * is set. Fixture names carry a "zz" prefix so they cannot collide with real dataset names
 * (name is the primary key). Fixture rows are inserted inside the test transaction, which @DataJpaTest rolls back, and
 * Flyway is disabled so a test can never migrate the real database.
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class IngredientSuggestDbTest {

    @Autowired
    private JdbcTemplate jdbc;

    // Built by hand instead of @Import-ed: an @Import changes the Spring test-context cache key, and a
    // second cached context opens a second Hikari pool, which exhausts the Supabase session-mode limit.
    private ReferenceDataRepository repository;

    @BeforeEach
    void repository() {
        repository = new ReferenceDataRepository(new NamedParameterJdbcTemplate(jdbc));
    }

    private void insertStats() {
        jdbc.update("INSERT INTO ingredient_stats (name, recipe_count) VALUES ('zzgarlic', 900), "
                + "('zzgarlic powder', 300), ('zzgaram masala', 50)");
    }

    @Test
    void suggestsByPrefixMostCommonFirst() {
        insertStats();

        List<String> result = repository.suggestIngredients("zzgar", 10);

        assertThat(result).containsSubsequence("zzgarlic", "zzgarlic powder", "zzgaram masala");
    }

    @Test
    void respectsTheLimit() {
        insertStats();

        assertThat(repository.suggestIngredients("zzgarl", 1)).hasSize(1);
    }

    @Test
    void likeWildcardsInThePrefixAreTakenLiterally() {
        insertStats();

        assertThat(repository.suggestIngredients("zzg_r", 10)).doesNotContain("zzgarlic", "zzgaram masala");
        assertThat(repository.suggestIngredients("%", 10)).doesNotContain("zzgarlic");
    }
}
