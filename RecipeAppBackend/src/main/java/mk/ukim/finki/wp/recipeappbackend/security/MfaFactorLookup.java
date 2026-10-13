package mk.ukim.finki.wp.recipeappbackend.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers "does this user have a verified two-factor factor?" by asking Supabase's auth.mfa_factors
 * table. The answer is cached per user for a short time so the check does not hit the database on
 * every request.
 *
 * The query only ever asks whether a row exists. It never selects the secret column, so a TOTP
 * secret can never be read, logged or leaked by this class.
 *
 * A failure is not caught here: a DataAccessException, or an IllegalStateException when the query
 * returns no result, goes to the caller, which must fail closed (refuse the request) rather than
 * assume the user has no factor. Failures are never cached.
 */
@Component
public class MfaFactorLookup {

    static final String SQL =
            "select exists(select 1 from auth.mfa_factors where user_id = ? and status = 'verified')";
    static final Duration TTL = Duration.ofSeconds(60);
    static final int MAX_ENTRIES = 10_000;

    private record Cached(boolean value, Instant expiresAt) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    /** Used by Spring. */
    @Autowired
    public MfaFactorLookup(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    /** Used by tests, which need to move the clock. */
    MfaFactorLookup(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean hasVerifiedFactor(UUID userId) {
        Instant now = clock.instant();
        Cached hit = cache.get(userId);
        if (hit != null && now.isBefore(hit.expiresAt())) {
            return hit.value();
        }
        Boolean result = jdbc.queryForObject(SQL, Boolean.class, userId);
        if (result == null) {
            // Fail closed: "no answer" must never be read as "no factor".
            throw new IllegalStateException("MFA factor lookup returned no result");
        }
        boolean value = result;
        if (cache.size() >= MAX_ENTRIES && !cache.containsKey(userId)) {
            cache.clear(); // simple bound on memory: start over when full
        }
        cache.put(userId, new Cached(value, now.plus(TTL)));
        return value;
    }

    int cacheSize() {
        return cache.size();
    }
}
