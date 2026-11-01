package mk.ukim.finki.wp.recipeappbackend.generation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import mk.ukim.finki.wp.recipeappbackend.repository.AiStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes old AI output once a day at 03:00 UTC: daily meal sets and generated recipes older than
 * 30 days that nobody saved and no remaining set uses (the repository holds the exact rules).
 *
 * A failure is logged and swallowed so it can never stop the scheduler thread; the next night
 * tries again.
 */
@Component
public class GeneratedRecipeCleanup {

    static final Duration KEEP_FOR = Duration.ofDays(30);

    private static final Logger log = LoggerFactory.getLogger(GeneratedRecipeCleanup.class);

    private final AiStateRepository state;
    private final Clock clock;

    /** Used by Spring. */
    @Autowired
    public GeneratedRecipeCleanup(AiStateRepository state) {
        this(state, Clock.systemUTC());
    }

    /** Used by tests, which need to fix the clock. */
    GeneratedRecipeCleanup(AiStateRepository state, Clock clock) {
        this.state = state;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @Transactional
    public void run() {
        try {
            Instant cutoff = clock.instant().minus(KEEP_FOR);
            OffsetDateTime cutoffTime = cutoff.atOffset(ZoneOffset.UTC);
            LocalDate cutoffDate = cutoffTime.toLocalDate();
            int[] removed = state.cleanupOlderThan(cutoffDate, cutoffTime);
            log.info("AI cleanup removed {} daily meal sets and {} generated recipes older than {}",
                    removed[0], removed[1], cutoffTime);
        } catch (RuntimeException e) {
            log.warn("AI cleanup failed, will retry at the next run: {}", e.toString());
        }
    }
}
