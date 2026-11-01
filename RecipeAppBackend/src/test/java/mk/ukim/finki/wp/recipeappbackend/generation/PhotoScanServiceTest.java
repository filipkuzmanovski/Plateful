package mk.ukim.finki.wp.recipeappbackend.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import mk.ukim.finki.wp.recipeappbackend.ai.AiImage;
import mk.ukim.finki.wp.recipeappbackend.ai.AiNotConfiguredException;
import mk.ukim.finki.wp.recipeappbackend.ai.AiQuotaExceededException;
import mk.ukim.finki.wp.recipeappbackend.ai.AiReply;
import mk.ukim.finki.wp.recipeappbackend.ai.AiRequest;
import mk.ukim.finki.wp.recipeappbackend.ai.AiUnavailableException;
import mk.ukim.finki.wp.recipeappbackend.ai.RecipeAi;
import mk.ukim.finki.wp.recipeappbackend.exception.InvalidScanPhotoException;
import mk.ukim.finki.wp.recipeappbackend.exception.NoPreferencesException;
import mk.ukim.finki.wp.recipeappbackend.exception.ScanFailedException;
import mk.ukim.finki.wp.recipeappbackend.exception.ScanLimitException;
import mk.ukim.finki.wp.recipeappbackend.food.FoodRules;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.ScanResultDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.UserPreferences;
import mk.ukim.finki.wp.recipeappbackend.repository.UserPreferencesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PhotoScanServiceTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final List<byte[]> PHOTOS = List.of(new byte[] {1, 2, 3});
    private static final AiImage IMAGE = new AiImage("image/jpeg", new byte[] {9, 9});

    private static final class MovingClock extends Clock {
        private Instant now = NOW;

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Replies (a String is the JSON, a RuntimeException is thrown) in order; remembers the requests. */
    private static final class ScriptedAi implements RecipeAi {
        final Deque<Object> script = new ArrayDeque<>();
        final List<AiRequest> requests = new ArrayList<>();
        final MovingClock clock;
        Duration perCall = Duration.ZERO;

        ScriptedAi(MovingClock clock) {
            this.clock = clock;
        }

        ScriptedAi then(Object... replies) {
            script.addAll(List.of(replies));
            return this;
        }

        @Override
        public AiReply complete(AiRequest request) {
            requests.add(request);
            clock.advance(perCall);
            Object next = script.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            if (next == null) {
                throw new IllegalStateException("the fake AI has no more replies");
            }
            return new AiReply((String) next, "groq", "test-model", 10, 20);
        }
    }

    private UserPreferencesRepository preferences;
    private AiUsageService usage;
    private PhotoPreparer preparer;
    private MovingClock clock;
    private ScriptedAi ai;
    private PhotoScanService service;

    @BeforeEach
    void setUp() {
        preferences = mock(UserPreferencesRepository.class);
        usage = mock(AiUsageService.class);
        preparer = mock(PhotoPreparer.class);
        clock = new MovingClock();
        ai = new ScriptedAi(clock);

        when(preferences.findById(USER)).thenReturn(Optional.of(UserPreferences.builder().userId(USER).build()));
        when(preparer.prepare(any())).thenReturn(List.of(IMAGE));
        when(usage.reserveScan(USER)).thenReturn(OptionalInt.of(1));

        FoodRules rules = new FoodRules();
        PantryStaples staples = new PantryStaples();
        service = new PhotoScanService(preferences, usage, preparer,
                new PromptBuilder(rules, staples, new Random(1)), ai, new ScanParser(),
                new SafetyCheck(rules, staples), 10, clock);
    }

    private static String json(String... names) {
        return Arrays.stream(names).map(n -> "\"" + n + "\"")
                .collect(Collectors.joining(",", "{\"ingredients\":[", "]}"));
    }

    private void withPreferences(UserPreferences p) {
        when(preferences.findById(USER)).thenReturn(Optional.of(p));
    }

    // ---- names ----

    @Test
    void normalisesDedupesKeepsOrder() {
        ai.then(json("Eggs", "egg", "Spinach", ""));

        ScanResultDto result = service.scan(USER, PHOTOS);

        assertThat(result.ingredients()).containsExactly("egg", "spinach");
        assertThat(result.leftOut()).isEmpty();
        verify(usage, never()).refundScan(any());
    }

    @Test
    void capsAtFifteen() {
        String[] twenty = IntStream.range(0, 20).mapToObj(i -> "ingredient " + (char) ('a' + i))
                .toArray(String[]::new);
        ai.then(json(twenty));

        ScanResultDto result = service.scan(USER, PHOTOS);

        assertThat(result.ingredients()).containsExactly(Arrays.copyOf(twenty, 15));
    }

    @Test
    void conflictsGoToLeftOut() {
        withPreferences(UserPreferences.builder().userId(USER).allergens(List.of("peanuts")).build());
        ai.then(json("peanut butter", "egg"));

        ScanResultDto result = service.scan(USER, PHOTOS);

        assertThat(result.ingredients()).containsExactly("egg");
        assertThat(result.leftOut()).containsExactly(
                new ScanResultDto.LeftOutDto("peanut butter", "allergy: Peanuts"));
    }

    @Test
    void leftOutKeepsOneRowPerReason() {
        withPreferences(UserPreferences.builder().userId(USER).allergens(List.of("milk"))
                .diets(List.of("vegan")).build());
        ai.then(json("cheese"));

        ScanResultDto result = service.scan(USER, PHOTOS);

        assertThat(result.ingredients()).isEmpty();
        assertThat(result.leftOut()).hasSize(2).extracting(ScanResultDto.LeftOutDto::ingredient)
                .containsOnly("cheese");
        assertThat(result.leftOut()).extracting(ScanResultDto.LeftOutDto::reason)
                .allMatch(r -> r.startsWith("allergy: ") || r.startsWith("diet: "));
    }

    @Test
    void conflictsAreRemovedBeforeTheCap() {
        withPreferences(UserPreferences.builder().userId(USER).allergens(List.of("peanuts")).build());
        List<String> names = new ArrayList<>(List.of("peanut butter"));
        IntStream.range(0, 15).forEach(i -> names.add("ingredient " + (char) ('a' + i)));
        ai.then(json(names.toArray(String[]::new)));

        ScanResultDto result = service.scan(USER, PHOTOS);

        assertThat(result.ingredients()).hasSize(15).doesNotContain("peanut butter");
        assertThat(result.leftOut()).hasSize(1);
    }

    @Test
    void emptyResult_isCountedNotRefunded() {
        ai.then(json());

        ScanResultDto result = service.scan(USER, PHOTOS);

        assertThat(result.ingredients()).isEmpty();
        assertThat(result.leftOut()).isEmpty();
        verify(usage, never()).refundScan(any());
    }

    // ---- unreadable replies ----

    @Test
    void unreadableThenReadable_succeedsWithOneScan() {
        ai.then("not json", json("egg"));

        ScanResultDto result = service.scan(USER, PHOTOS);

        assertThat(result.ingredients()).containsExactly("egg");
        assertThat(ai.requests).hasSize(2);
        verify(usage, times(1)).reserveScan(USER);
        verify(usage, never()).refundScan(any());
    }

    @Test
    void unreadableTwice_is422AndRefunded() {
        ai.then("not json", "{\"nothing\":1}");

        assertThatThrownBy(() -> service.scan(USER, PHOTOS)).isInstanceOf(ScanFailedException.class);

        assertThat(ai.requests).hasSize(2);
        verify(usage, times(1)).refundScan(USER);
    }

    @Test
    void noRetryUnder20SecondsLeft() {
        ai.perCall = Duration.ofSeconds(26);
        ai.then("not json", json("egg"));

        assertThatThrownBy(() -> service.scan(USER, PHOTOS)).isInstanceOf(ScanFailedException.class);

        assertThat(ai.requests).hasSize(1);
        verify(usage, times(1)).refundScan(USER);
    }

    @Test
    void retryWhenExactly20SecondsLeft() {
        ai.perCall = Duration.ofSeconds(25);
        ai.then("not json", json("egg"));

        assertThat(service.scan(USER, PHOTOS).ingredients()).containsExactly("egg");
        assertThat(ai.requests).hasSize(2);
    }

    // ---- failures are refunded ----

    @Test
    void quota_isRefundedAndRethrown() {
        ai.then(new AiQuotaExceededException("quota"));
        assertThatThrownBy(() -> service.scan(USER, PHOTOS)).isInstanceOf(AiQuotaExceededException.class);
        verify(usage, times(1)).refundScan(USER);
    }

    @Test
    void unavailable_isRefundedAndRethrown() {
        ai.then(new AiUnavailableException("down"));
        assertThatThrownBy(() -> service.scan(USER, PHOTOS)).isInstanceOf(AiUnavailableException.class);
        verify(usage, times(1)).refundScan(USER);
    }

    @Test
    void notConfigured_isRefundedAndRethrown() {
        ai.then(new AiNotConfiguredException("no key"));
        assertThatThrownBy(() -> service.scan(USER, PHOTOS)).isInstanceOf(AiNotConfiguredException.class);
        verify(usage, times(1)).refundScan(USER);
    }

    @Test
    void staleProfileKey_isRefundedAndRethrown() {
        withPreferences(UserPreferences.builder().userId(USER).allergens(List.of("no_such_allergen")).build());
        ai.then(json("egg"));

        assertThatThrownBy(() -> service.scan(USER, PHOTOS)).isInstanceOf(IllegalArgumentException.class);

        verify(usage, times(1)).refundScan(USER);
    }

    @Test
    void failedRefund_doesNotHideTheOriginalError() {
        ai.then(new AiUnavailableException("down"));
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(usage).refundScan(USER);

        assertThatThrownBy(() -> service.scan(USER, PHOTOS)).isInstanceOf(AiUnavailableException.class);
    }

    // ---- before the AI ----

    @Test
    void invalidPhoto_noReservationNoAiCall() {
        when(preparer.prepare(any())).thenThrow(new InvalidScanPhotoException("bad"));

        assertThatThrownBy(() -> service.scan(USER, PHOTOS)).isInstanceOf(InvalidScanPhotoException.class);

        verifyNoInteractions(usage);
        assertThat(ai.requests).isEmpty();
    }

    @Test
    void noPreferences_is409BeforeAnything() {
        when(preferences.findById(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.scan(USER, PHOTOS)).isInstanceOf(NoPreferencesException.class);

        verifyNoInteractions(preparer, usage);
        assertThat(ai.requests).isEmpty();
    }

    @Test
    void limitReached_isScanLimitWithNoAiCall() {
        when(usage.reserveScan(USER)).thenReturn(OptionalInt.empty());

        assertThatThrownBy(() -> service.scan(USER, PHOTOS)).isInstanceOf(ScanLimitException.class)
                .hasMessage("You've used today's 10 photo scans. More tomorrow.");

        assertThat(ai.requests).isEmpty();
        verify(usage, never()).refundScan(any());
    }

    @Test
    void remainingScans_isLimitMinusReservedCount() {
        when(usage.reserveScan(USER)).thenReturn(OptionalInt.of(3));
        ai.then(json("egg"));

        assertThat(service.scan(USER, PHOTOS).remainingScans()).isEqualTo(7);
    }

    @Test
    void remainingScans_neverNegative() {
        when(usage.reserveScan(USER)).thenReturn(OptionalInt.of(12));
        ai.then(json("egg"));

        assertThat(service.scan(USER, PHOTOS).remainingScans()).isZero();
    }

    // ---- the request and the logs ----

    @Test
    void sendsTheScanPromptWithThePreparedImages() {
        ai.then(json("egg"));

        service.scan(USER, PHOTOS);

        assertThat(ai.requests).hasSize(1);
        AiRequest request = ai.requests.get(0);
        assertThat(request.images()).containsExactly(IMAGE);
        assertThat(request.userText()).isEqualTo("Here are 1 photo. List the ingredients.");
    }

    @Test
    void logsCountsOnly() {
        Logger logger = (Logger) LoggerFactory.getLogger(PhotoScanService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            withPreferences(UserPreferences.builder().userId(USER).allergens(List.of("peanuts")).build());
            ai.then(json("peanut butter", "dragonfruit"));

            service.scan(USER, PHOTOS);

            List<String> lines = appender.list.stream().filter(e -> e.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(lines).hasSize(1);
            assertThat(lines.get(0)).doesNotContain("dragonfruit").doesNotContain("peanut")
                    .doesNotContain(USER.toString());
        } finally {
            logger.detachAppender(appender);
        }
    }
}
