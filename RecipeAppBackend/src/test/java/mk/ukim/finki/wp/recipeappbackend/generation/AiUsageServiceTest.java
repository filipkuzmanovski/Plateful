package mk.ukim.finki.wp.recipeappbackend.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.OptionalInt;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.ai.AiProperties;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.AiUsageDto;
import mk.ukim.finki.wp.recipeappbackend.repository.AiStateRepository;
import org.junit.jupiter.api.Test;

class AiUsageServiceTest {

    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    // 23:59 UTC on 31 October: already the 1st of November in Skopje, still the 31st in UTC.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T23:59:30Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 31);

    private final AiStateRepository state = mock(AiStateRepository.class);
    private final AiUsageService service =
            new AiUsageService(state, new AiProperties(null, null, null, 10), CLOCK);

    @Test
    void tryReserve_trueWhenTheRepositoryGaveACount_usingTheUtcDayAndTheLimit() {
        when(state.reserveGeneration(USER, TODAY, 10)).thenReturn(OptionalInt.of(4));

        assertThat(service.tryReserve(USER)).isTrue();
        assertThat(service.reserve(USER)).hasValue(4);
    }

    @Test
    void tryReserve_falseWhenTheLimitIsReached() {
        when(state.reserveGeneration(USER, TODAY, 10)).thenReturn(OptionalInt.empty());

        assertThat(service.tryReserve(USER)).isFalse();
    }

    @Test
    void refund_givesBackForTheUtcDay() {
        service.refund(USER);

        verify(state).refundGeneration(USER, TODAY);
    }

    @Test
    void usage_reportsUsedLimitAndRemaining() {
        when(state.generationsUsed(USER, TODAY)).thenReturn(3);

        assertThat(service.usage(USER)).isEqualTo(new AiUsageDto(3, 10, 7));
    }

    @Test
    void usage_remainingNeverGoesBelowZero() {
        when(state.generationsUsed(USER, TODAY)).thenReturn(12);

        assertThat(service.usage(USER)).isEqualTo(new AiUsageDto(12, 10, 0));
    }

    @Test
    void usage_fullyUnusedDay() {
        assertThat(service.usage(USER)).isEqualTo(new AiUsageDto(0, 10, 10));
    }
}
