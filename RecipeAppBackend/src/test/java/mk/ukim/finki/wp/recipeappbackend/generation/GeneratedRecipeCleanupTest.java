package mk.ukim.finki.wp.recipeappbackend.generation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import mk.ukim.finki.wp.recipeappbackend.repository.AiStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class GeneratedRecipeCleanupTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-30T03:00:00Z"), ZoneOffset.UTC);

    private final AiStateRepository state = mock(AiStateRepository.class);
    private final GeneratedRecipeCleanup cleanup = new GeneratedRecipeCleanup(state, CLOCK);

    @Test
    void run_cleansEverythingOlderThanThirtyDays() {
        when(state.cleanupOlderThan(LocalDate.of(2026, 10, 31),
                OffsetDateTime.parse("2026-10-31T03:00:00Z"))).thenReturn(new int[]{2, 5});

        cleanup.run();

        verify(state).cleanupOlderThan(LocalDate.of(2026, 10, 31),
                OffsetDateTime.of(2026, 10, 31, 3, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void run_swallowsAFailureSoTheSchedulerKeepsRunning() {
        when(state.cleanupOlderThan(LocalDate.of(2026, 10, 31),
                OffsetDateTime.parse("2026-10-31T03:00:00Z")))
                .thenThrow(new DataAccessResourceFailureException("database is down"));

        assertThatCode(cleanup::run).doesNotThrowAnyException();
    }
}
