package mk.ukim.finki.wp.recipeappbackend.generation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.ai.AiImage;
import mk.ukim.finki.wp.recipeappbackend.ai.AiProperties;
import mk.ukim.finki.wp.recipeappbackend.ai.AiReply;
import mk.ukim.finki.wp.recipeappbackend.ai.RecipeAi;
import mk.ukim.finki.wp.recipeappbackend.exception.NoPreferencesException;
import mk.ukim.finki.wp.recipeappbackend.exception.ScanFailedException;
import mk.ukim.finki.wp.recipeappbackend.exception.ScanLimitException;
import mk.ukim.finki.wp.recipeappbackend.food.IngredientNames;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.ScanResultDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.UserPreferences;
import mk.ukim.finki.wp.recipeappbackend.repository.UserPreferencesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * "Scan my fridge" (spec sections 4.2 and 5.3): preferences, photo preparation, a reserved daily scan, the
 * vision call (one retry if the reply is unreadable and enough time is left), then names normalised,
 * filtered against the user's preferences and capped.
 *
 * <p>No database transaction or connection is held while the AI call runs: preferences are read and the scan
 * is reserved as their own statements. Whatever ends the scan without a readable reply (quota, an outage,
 * a missing key, an unreadable reply, a bug) gives the scan back before the exception goes on. A readable
 * reply that names no food is a normal result and still counts.
 *
 * <p>Nothing about the user reaches the provider, and nothing about the photos or the ingredients reaches
 * the log: the one INFO line per scan holds counts only.
 */
@Service
public class PhotoScanService {

    private static final Logger log = LoggerFactory.getLogger(PhotoScanService.class);

    static final Duration BUDGET = Duration.ofSeconds(45);
    static final Duration MIN_TIME_FOR_RETRY = Duration.ofSeconds(20);
    static final int MAX_RESULTS = 15;
    static final int MAX_ATTEMPTS = 2;

    private final UserPreferencesRepository preferences;
    private final AiUsageService usage;
    private final PhotoPreparer preparer;
    private final PromptBuilder prompts;
    private final RecipeAi ai;
    private final ScanParser parser;
    private final SafetyCheck safety;
    private final int scanLimit;
    private final Clock clock;

    @Autowired
    public PhotoScanService(UserPreferencesRepository preferences, AiUsageService usage, PhotoPreparer preparer,
                            PromptBuilder prompts, @Qualifier("visionAi") RecipeAi ai, ScanParser parser,
                            SafetyCheck safety, AiProperties properties) {
        this(preferences, usage, preparer, prompts, ai, parser, safety, properties.dailyScanLimit(),
                Clock.systemUTC());
    }

    PhotoScanService(UserPreferencesRepository preferences, AiUsageService usage, PhotoPreparer preparer,
                     PromptBuilder prompts, RecipeAi ai, ScanParser parser, SafetyCheck safety, int scanLimit,
                     Clock clock) {
        this.preferences = preferences;
        this.usage = usage;
        this.preparer = preparer;
        this.prompts = prompts;
        this.ai = ai;
        this.parser = parser;
        this.safety = safety;
        this.scanLimit = scanLimit;
        this.clock = clock;
    }

    public ScanResultDto scan(UUID userId, List<byte[]> photos) {
        UserPreferences stored = preferences.findById(userId).orElseThrow(NoPreferencesException::new);
        UserFoodProfile profile = UserFoodProfile.of(stored);

        List<AiImage> images = preparer.prepare(photos);

        OptionalInt reserved = usage.reserveScan(userId);
        if (reserved.isEmpty()) {
            throw new ScanLimitException();
        }
        try {
            return identify(images, profile, reserved.getAsInt());
        } catch (RuntimeException e) {
            log.info("photo scan photos={} outcome=failed reason={}", images.size(), e.getClass().getSimpleName());
            refundQuietly(userId);
            throw e;
        }
    }

    /** Spec section 4.2 steps 4 to 8. */
    private ScanResultDto identify(List<AiImage> images, UserFoodProfile profile, int reservedCount) {
        Instant start = clock.instant();
        List<String> found = askForNames(images, start);

        // Step 5: normalise, drop empties, remove duplicates, keep order.
        Set<String> names = new LinkedHashSet<>();
        for (String raw : found) {
            IngredientNames.normalise(raw).ifPresent(names::add);
        }

        // Step 6: conflicts leave the list and are reported (one row per reason).
        List<SafetyCheck.Conflict> conflicts = safety.conflicts(names, profile);
        Set<String> conflicting = new LinkedHashSet<>();
        List<ScanResultDto.LeftOutDto> leftOut = new ArrayList<>();
        for (SafetyCheck.Conflict conflict : conflicts) {
            conflicting.add(conflict.ingredient());
            leftOut.add(new ScanResultDto.LeftOutDto(conflict.ingredient(), conflict.reason()));
        }

        // Step 7: keep the first 15.
        List<String> ingredients = names.stream().filter(n -> !conflicting.contains(n)).limit(MAX_RESULTS).toList();

        log.info("photo scan photos={} outcome=ok found={} leftOut={} returned={}", images.size(), names.size(),
                conflicting.size(), ingredients.size());
        return new ScanResultDto(ingredients, leftOut, Math.max(0, scanLimit - reservedCount));
    }

    /** Step 4: the vision call, retried once if the reply is unreadable and 20 s of the 45 s budget remain. */
    private List<String> askForNames(List<AiImage> images, Instant start) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (attempt > 1 && timeLeft(start).compareTo(MIN_TIME_FOR_RETRY) < 0) {
                break;
            }
            AiReply reply = ai.complete(prompts.scan(images.size(), images));
            Optional<List<String>> parsed = parser.parse(reply.json());
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }
        throw new ScanFailedException();
    }

    private Duration timeLeft(Instant start) {
        return BUDGET.minus(Duration.between(start, clock.instant()));
    }

    /** A failed refund must not hide the error that ended the scan. */
    private void refundQuietly(UUID userId) {
        try {
            usage.refundScan(userId);
        } catch (RuntimeException e) {
            log.error("Could not refund a photo scan", e);
        }
    }
}
