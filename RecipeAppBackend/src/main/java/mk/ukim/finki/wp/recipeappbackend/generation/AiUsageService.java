package mk.ukim.finki.wp.recipeappbackend.generation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.OptionalInt;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.ai.AiProperties;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.AiUsageDto;
import mk.ukim.finki.wp.recipeappbackend.repository.AiStateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The per-user, per-UTC-day count of AI generations (spec section 4.2). Each call is one statement in
 * {@link AiStateRepository}; nothing here holds a transaction or connection.
 */
@Service
public class AiUsageService {

    private final AiStateRepository state;
    private final AiProperties properties;
    private final Clock clock;

    @Autowired
    public AiUsageService(AiStateRepository state, AiProperties properties) {
        this(state, properties, Clock.systemUTC());
    }

    AiUsageService(AiStateRepository state, AiProperties properties, Clock clock) {
        this.state = state;
        this.properties = properties;
        this.clock = clock;
    }

    /** Takes one generation for today. False when today's limit is already reached. */
    public boolean tryReserve(UUID userId) {
        return reserve(userId).isPresent();
    }

    /** Takes one generation for today; the count including it, or empty when the limit is reached. */
    public OptionalInt reserve(UUID userId) {
        return state.reserveGeneration(userId, today(), properties.dailyLimit());
    }

    /** Gives one generation back; a failed generation is free. */
    public void refund(UUID userId) {
        state.refundGeneration(userId, today());
    }

    public AiUsageDto usage(UUID userId) {
        int limit = properties.dailyLimit();
        int used = state.generationsUsed(userId, today());
        return new AiUsageDto(used, limit, Math.max(0, limit - used));
    }

    private LocalDate today() {
        return clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
    }
}
