package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.security.MfaFactorLookup;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Runs the real factor query against the real Supabase Postgres, so a wrong table, column or enum
 * value shows up here and not as a 503 in production. Opt-in: skipped unless SUPABASE_DB_PASSWORD
 * is set. Read-only; Flyway is disabled so a test can never migrate the real database.
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class MfaFactorLookupDbTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void randomUserHasNoFactor() {
        MfaFactorLookup lookup = new MfaFactorLookup(jdbcTemplate);
        assertThat(lookup.hasVerifiedFactor(UUID.randomUUID())).isFalse();
    }
}
