package mk.ukim.finki.wp.recipeappbackend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MfaFactorLookupTest {

    /** A clock whose time the test can move forward. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private JdbcTemplate jdbc;
    private MutableClock clock;
    private MfaFactorLookup lookup;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        clock = new MutableClock();
        when(jdbc.queryForObject(eq(MfaFactorLookup.SQL), eq(Boolean.class), any(UUID.class))).thenReturn(true);
        lookup = new MfaFactorLookup(jdbc, clock);
    }

    @Test
    void cachesWithinTtl() {
        UUID id = UUID.randomUUID();
        assertThat(lookup.hasVerifiedFactor(id)).isTrue();
        clock.advance(Duration.ofSeconds(30));
        assertThat(lookup.hasVerifiedFactor(id)).isTrue();
        verify(jdbc, times(1)).queryForObject(MfaFactorLookup.SQL, Boolean.class, id);
    }

    @Test
    void requeriesAfterTtl() {
        UUID id = UUID.randomUUID();
        lookup.hasVerifiedFactor(id);
        clock.advance(Duration.ofSeconds(61));
        lookup.hasVerifiedFactor(id);
        verify(jdbc, times(2)).queryForObject(MfaFactorLookup.SQL, Boolean.class, id);
    }

    @Test
    void perUserCache() {
        lookup.hasVerifiedFactor(UUID.randomUUID());
        lookup.hasVerifiedFactor(UUID.randomUUID());
        verify(jdbc, times(2)).queryForObject(eq(MfaFactorLookup.SQL), eq(Boolean.class), any(UUID.class));
    }

    @Test
    void sqlNeverTouchesSecret() {
        assertThat(MfaFactorLookup.SQL).doesNotContain("secret").contains("status = 'verified'");
    }

    @Test
    void clearsWhenFull() {
        for (int i = 0; i <= MfaFactorLookup.MAX_ENTRIES; i++) {
            lookup.hasVerifiedFactor(UUID.randomUUID());
        }
        assertThat(lookup.cacheSize()).isLessThanOrEqualTo(MfaFactorLookup.MAX_ENTRIES);
    }

    @Test
    void exceptionPropagatesAndIsNotCached() {
        UUID id = UUID.randomUUID();
        when(jdbc.queryForObject(eq(MfaFactorLookup.SQL), eq(Boolean.class), any(UUID.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"))
                .thenReturn(true);

        assertThatThrownBy(() -> lookup.hasVerifiedFactor(id))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(lookup.hasVerifiedFactor(id)).isTrue();
        verify(jdbc, times(2)).queryForObject(MfaFactorLookup.SQL, Boolean.class, id);
    }

    @Test
    void nullResultThrows() {
        UUID id = UUID.randomUUID();
        when(jdbc.queryForObject(eq(MfaFactorLookup.SQL), eq(Boolean.class), any(UUID.class))).thenReturn(null);

        assertThatThrownBy(() -> lookup.hasVerifiedFactor(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MFA factor lookup returned no result");
        assertThat(lookup.cacheSize()).isZero();
    }
}
