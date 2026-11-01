package mk.ukim.finki.wp.recipeappbackend.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import mk.ukim.finki.wp.recipeappbackend.ai.AiProperties;
import mk.ukim.finki.wp.recipeappbackend.ai.AiQuotaExceededException;
import mk.ukim.finki.wp.recipeappbackend.ai.AiReply;
import mk.ukim.finki.wp.recipeappbackend.ai.AiRequest;
import mk.ukim.finki.wp.recipeappbackend.ai.AiUnavailableException;
import mk.ukim.finki.wp.recipeappbackend.ai.RecipeAi;
import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.exception.DailyLimitException;
import mk.ukim.finki.wp.recipeappbackend.exception.GenerationFailedException;
import mk.ukim.finki.wp.recipeappbackend.exception.InvalidScanPhotoException;
import mk.ukim.finki.wp.recipeappbackend.exception.NoDishException;
import mk.ukim.finki.wp.recipeappbackend.exception.NoPreferencesException;
import mk.ukim.finki.wp.recipeappbackend.exception.PostImportRequestException;
import mk.ukim.finki.wp.recipeappbackend.food.FoodRules;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GenerateResultDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.GeneratedRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.model.entities.UserPreferences;
import mk.ukim.finki.wp.recipeappbackend.repository.AiStateRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.GeneratedRecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserPreferencesRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class PostImportServiceTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);
    private static final String NOT_JSON = "this is not json";
    private static final String CAPTION = "My grandmother's secret charred corn salad, so good";
    private static final String READING = "{\"dish\":\"Charred Corn Salad\","
            + "\"description\":\"Golden corn kernels with white crumbles.\","
            + "\"ingredients\":[\"4 ears sweet corn\"],\"steps\":[\"Grill the cobs over high heat.\"]}";
    private static final String EMPTY_READING = "{\"dish\":null,\"description\":\"\",\"ingredients\":[],\"steps\":[]}";
    private static final String DETAIL = "Add a caption (up to 2,200 characters) or 1 to 2 screenshots.";
    private static final String NO_DISH = "{\"origin\":\"none\"}";
    private static final List<String> STEPS = List.of("Char the corn in a hot dry pan.", "Toss with feta and oil.");

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
    private UserRepository users;
    private GeneratedRecipeRepository recipes;
    private AiStateRepository state;
    private PlatformTransactionManager transactionManager;
    private MovingClock clock;
    private ScriptedAi recipeAi;
    private ScriptedAi visionAi;
    private ScriptedAi postAi;
    private PostImportService service;

    @BeforeEach
    void setUp() {
        preferences = mock(UserPreferencesRepository.class);
        users = mock(UserRepository.class);
        recipes = mock(GeneratedRecipeRepository.class);
        state = mock(AiStateRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        clock = new MovingClock();
        recipeAi = new ScriptedAi(clock);
        visionAi = new ScriptedAi(clock);
        postAi = new ScriptedAi(clock);

        when(preferences.findById(USER)).thenReturn(Optional.of(UserPreferences.builder().userId(USER).build()));
        when(users.getReferenceById(any())).thenReturn(mock(User.class));
        when(recipes.save(any(GeneratedRecipe.class))).thenAnswer(inv -> {
            GeneratedRecipe row = inv.getArgument(0);
            row.setId(UUID.randomUUID());
            return row;
        });
        when(state.reserveGeneration(any(), any(), anyInt())).thenReturn(OptionalInt.of(3));

        FoodRules rules = new FoodRules();
        PantryStaples staples = new PantryStaples();
        DraftParser draftParser = new DraftParser(new CuisineCatalog());
        service = new PostImportService(
                preferences, users, recipes,
                new AiUsageService(state, new AiProperties(null, null, null, 10, 10), clock),
                new PhotoPreparer(), new PromptBuilder(rules, staples, new Random(1)),
                visionAi, postAi, recipeAi, new PostReadingParser(), new PostReplyParser(draftParser),
                new SafetyCheck(rules, staples), new GeneratedRecipeMapper(rules),
                new TransactionTemplate(transactionManager), 10, clock);
    }

    // ---- helpers ----

    private static byte[] jpeg() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "jpg", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<byte[]> screenshots(int n) {
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(jpeg());
        }
        return out;
    }

    private static String reply(String origin, String swaps, List<String> ingredientNames, List<String> steps) {
        String ingredients = ingredientNames.stream()
                .map(n -> "{\"name\":\"" + n + "\",\"amount\":\"1 portion\"}").collect(Collectors.joining(","));
        String stepList = steps.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(","));
        return "{\"origin\":\"" + origin + "\",\"swaps\":" + swaps + ",\"title\":\"Charred Corn and Feta Salad\","
                + "\"description\":\"A bright summer salad.\",\"servings\":2,\"prepMinutes\":10,\"cookMinutes\":10,"
                + "\"cuisine\":null,\"ingredients\":[" + ingredients + "],\"steps\":[" + stepList + "]}";
    }

    private static String goodReply() {
        return reply("recipe", "[{\"from\":\"butter\",\"to\":\"olive oil\"}]",
                List.of("sweet corn", "feta", "olive oil"), STEPS);
    }

    private GeneratedRecipe savedRow() {
        ArgumentCaptor<GeneratedRecipe> captor = ArgumentCaptor.forClass(GeneratedRecipe.class);
        verify(recipes).save(captor.capture());
        return captor.getValue();
    }

    private void withPreferences(UserPreferences p) {
        when(preferences.findById(USER)).thenReturn(Optional.of(p));
    }

    private void assertNothingReservedOrCalled() {
        verifyNoInteractions(state, recipes);
        assertThat(recipeAi.requests).isEmpty();
        assertThat(visionAi.requests).isEmpty();
        assertThat(postAi.requests).isEmpty();
    }

    // ---- routing ----

    @Test
    void captionOnly_usesRecipeAiOnly() {
        recipeAi.then(goodReply());

        GenerateResultDto result = service.importPost(USER, CAPTION, List.of());

        assertThat(result.recipe().kind()).isEqualTo("post");
        assertThat(recipeAi.requests).hasSize(1);
        assertThat(recipeAi.requests.get(0).userText()).contains("<post-caption>\n" + CAPTION + "\n</post-caption>")
                .doesNotContain("post-screenshots");
        assertThat(visionAi.requests).isEmpty();
        assertThat(postAi.requests).isEmpty();
        assertThat(savedRow().getRequest()).isEqualTo(Map.of("screenshots", 0, "caption", true));
    }

    @Test
    void screenshots_readWithVisionThenWriteWithPostAi() {
        visionAi.then(READING);
        postAi.then(goodReply());

        service.importPost(USER, CAPTION, screenshots(2));

        assertThat(visionAi.requests).hasSize(1);
        assertThat(visionAi.requests.get(0).images()).hasSize(2);
        assertThat(visionAi.requests.get(0).userText()).isEqualTo("Here are 2 screenshots of one post.");
        assertThat(postAi.requests).hasSize(1);
        assertThat(postAi.requests.get(0).userText()).contains("<post-caption>").contains("<post-screenshots>")
                .contains("Dish: Charred Corn Salad");
        assertThat(postAi.requests.get(0).images()).isEmpty();
        assertThat(recipeAi.requests).isEmpty();
        assertThat(savedRow().getRequest()).isEqualTo(Map.of("screenshots", 2, "caption", true));
    }

    @Test
    void blankCaptionWithScreenshot_isScreenshotOnly() {
        visionAi.then(READING);
        postAi.then(goodReply());

        service.importPost(USER, "   \n", screenshots(1));

        assertThat(postAi.requests).hasSize(1);
        assertThat(postAi.requests.get(0).userText()).doesNotContain("post-caption");
        assertThat(recipeAi.requests).isEmpty();
        assertThat(savedRow().getRequest()).isEqualTo(Map.of("screenshots", 1, "caption", false));
    }

    // ---- reading ----

    @Test
    void unreadableThenReadableReading_succeeds() {
        visionAi.then(NOT_JSON, READING);
        postAi.then(goodReply());

        GenerateResultDto result = service.importPost(USER, null, screenshots(1));

        assertThat(result.recipe().title()).isEqualTo("Charred Corn and Feta Salad");
        assertThat(visionAi.requests).hasSize(2);
        verify(state, never()).refundGeneration(any(), any());
    }

    @Test
    void emptyReading_isPassedToTheWriter() {
        visionAi.then(EMPTY_READING);
        postAi.then(NO_DISH);

        assertThatThrownBy(() -> service.importPost(USER, null, screenshots(1)))
                .isInstanceOf(NoDishException.class);

        assertThat(visionAi.requests).hasSize(1);
        assertThat(postAi.requests).hasSize(1);
        verify(state).refundGeneration(USER, TODAY);
    }

    @Test
    void unreadableReadingTwice_isNoDishAndRefunds() {
        visionAi.then(NOT_JSON, NOT_JSON);

        assertThatThrownBy(() -> service.importPost(USER, CAPTION, screenshots(1)))
                .isInstanceOf(NoDishException.class);

        assertThat(visionAi.requests).hasSize(2);
        assertThat(postAi.requests).isEmpty();
        assertThat(recipeAi.requests).isEmpty();
        verify(state).refundGeneration(USER, TODAY);
        verify(recipes, never()).save(any());
    }

    @Test
    void noReadingRetryUnder60sLeft() {
        // The first reading ends at 31 s: 59 s left, under the 60 s a retry needs.
        visionAi.perCall = Duration.ofSeconds(31);
        visionAi.then(NOT_JSON, READING);

        assertThatThrownBy(() -> service.importPost(USER, null, screenshots(1)))
                .isInstanceOf(NoDishException.class);

        assertThat(visionAi.requests).hasSize(1);
        verify(state).refundGeneration(USER, TODAY);
    }

    @Test
    void readingRetryWithExactly60sLeft() {
        visionAi.perCall = Duration.ofSeconds(30);
        visionAi.then(NOT_JSON, READING);
        postAi.then(goodReply());

        service.importPost(USER, null, screenshots(1));

        assertThat(visionAi.requests).hasSize(2);
    }

    // ---- writing ----

    @Test
    void failedCheckRetriesWithReason() {
        withPreferences(UserPreferences.builder().userId(USER).dislikedIngredients(List.of("cilantro")).build());
        recipeAi.then(reply("recipe", "[]", List.of("sweet corn", "feta", "cilantro"), STEPS), goodReply());

        GenerateResultDto result = service.importPost(USER, CAPTION, List.of());

        assertThat(result.recipe().ingredients()).extracting("name").doesNotContain("cilantro");
        assertThat(recipeAi.requests).hasSize(2);
        assertThat(recipeAi.requests.get(0).userText()).doesNotContain(PromptBuilder.RETRY_HEADING);
        assertThat(recipeAi.requests.get(1).userText()).contains(PromptBuilder.RETRY_HEADING)
                .contains("The recipe used cilantro, which is not allowed.");
        verify(state, never()).refundGeneration(any(), any());
    }

    @Test
    void threeFailures_isGenerationFailedAndRefunds() {
        recipeAi.then(NOT_JSON, NOT_JSON, NOT_JSON);

        assertThatThrownBy(() -> service.importPost(USER, CAPTION, List.of()))
                .isInstanceOf(GenerationFailedException.class);

        assertThat(recipeAi.requests).hasSize(3);
        verify(state).refundGeneration(USER, TODAY);
        verify(recipes, never()).save(any());
    }

    @Test
    void noAttemptUnder30sLeft() {
        // The reading ends at 61 s: 29 s left, so no writing attempt starts at all.
        visionAi.perCall = Duration.ofSeconds(61);
        visionAi.then(READING);
        postAi.then(goodReply());

        assertThatThrownBy(() -> service.importPost(USER, null, screenshots(1)))
                .isInstanceOf(GenerationFailedException.class);

        assertThat(postAi.requests).isEmpty();
        verify(state).refundGeneration(USER, TODAY);
        verify(recipes, never()).save(any());
    }

    @Test
    void noSecondAttemptUnder30sLeft() {
        // Attempt 1 ends at 31 s (59 s left: attempt 2 starts), attempt 2 ends at 62 s (28 s left: stop).
        recipeAi.perCall = Duration.ofSeconds(31);
        recipeAi.then(NOT_JSON, NOT_JSON, goodReply());

        assertThatThrownBy(() -> service.importPost(USER, CAPTION, List.of()))
                .isInstanceOf(GenerationFailedException.class);

        assertThat(recipeAi.requests).hasSize(2);
        verify(state).refundGeneration(USER, TODAY);
    }

    @Test
    void originNone_isNoDishAndRefunds() {
        recipeAi.then(NO_DISH, goodReply());

        assertThatThrownBy(() -> service.importPost(USER, CAPTION, List.of()))
                .isInstanceOf(NoDishException.class);

        assertThat(recipeAi.requests).hasSize(1);
        verify(state).refundGeneration(USER, TODAY);
        verify(recipes, never()).save(any());
    }

    @Test
    void unsafeSwapTarget_isRetried() {
        withPreferences(UserPreferences.builder().userId(USER).allergens(List.of("peanuts")).build());
        recipeAi.then(reply("recipe", "[{\"from\":\"butter\",\"to\":\"peanut butter\"}]",
                List.of("sweet corn", "feta", "olive oil"), STEPS), goodReply());

        GenerateResultDto result = service.importPost(USER, CAPTION, List.of());

        assertThat(recipeAi.requests).hasSize(2);
        assertThat(recipeAi.requests.get(1).userText()).contains(PromptBuilder.RETRY_HEADING)
                .contains("The swap to peanut butter is not allowed.");
        assertThat(result.recipe().swaps()).containsExactly(new StoredRecipe.Swap("butter", "olive oil"));
    }

    @Test
    void copiedStep_isRetried() {
        String sentence = "Char the corn in a very hot dry pan until the kernels blister and pop loudly";
        recipeAi.then(reply("recipe", "[]", List.of("sweet corn", "feta", "olive oil"),
                        List.of(sentence + ".", "Toss with feta and oil.")),
                goodReply());

        service.importPost(USER, "Summer lunch! " + sentence + ", then add feta.", List.of());

        assertThat(recipeAi.requests).hasSize(2);
        assertThat(recipeAi.requests.get(1).userText())
                .contains("Step 1 copies the post's wording. Rewrite it in your own words.");
    }

    @Test
    void copiedReadingStep_isRetried() {
        String sentence = "Grill the cobs over high heat turning often until every side is charred and sweet";
        visionAi.then("{\"dish\":\"Corn Salad\",\"description\":\"\",\"ingredients\":[],\"steps\":[\""
                + sentence + "\"]}");
        postAi.then(reply("recipe", "[]", List.of("sweet corn", "feta", "olive oil"),
                List.of("First, " + sentence.toLowerCase() + ".", "Toss with feta and oil.")), goodReply());

        service.importPost(USER, null, screenshots(1));

        assertThat(postAi.requests).hasSize(2);
        assertThat(postAi.requests.get(1).userText()).contains("Step 1 copies the post's wording.");
    }

    // ---- AI errors ----

    @Test
    void quota_isRethrownAndRefunds() {
        visionAi.then(READING);
        postAi.then(new AiQuotaExceededException("quota"));

        assertThatThrownBy(() -> service.importPost(USER, CAPTION, screenshots(1)))
                .isInstanceOf(AiQuotaExceededException.class);

        verify(state).refundGeneration(USER, TODAY);
        verify(recipes, never()).save(any());
    }

    @Test
    void unavailable_isRethrownAndRefunds() {
        visionAi.then(new AiUnavailableException("timeout"));

        assertThatThrownBy(() -> service.importPost(USER, CAPTION, screenshots(1)))
                .isInstanceOf(AiUnavailableException.class);

        assertThat(postAi.requests).isEmpty();
        verify(state).refundGeneration(USER, TODAY);
        verify(recipes, never()).save(any());
    }

    @Test
    void saveFails_refunds() {
        when(recipes.save(any(GeneratedRecipe.class))).thenThrow(new IllegalStateException("db down"));
        recipeAi.then(goodReply());

        assertThatThrownBy(() -> service.importPost(USER, CAPTION, List.of()))
                .isInstanceOf(IllegalStateException.class);

        verify(state).refundGeneration(USER, TODAY);
    }

    // ---- input ----

    @Test
    void noCaptionNoScreenshots_is400NoReservation() {
        for (String caption : new String[] {null, "", " \t\r\n ", "<post-caption></post-caption>"}) {
            assertThatThrownBy(() -> service.importPost(USER, caption, List.of()))
                    .isInstanceOf(PostImportRequestException.class).hasMessage(DETAIL);
            assertThatThrownBy(() -> service.importPost(USER, caption, null))
                    .isInstanceOf(PostImportRequestException.class).hasMessage(DETAIL);
        }
        assertNothingReservedOrCalled();
    }

    @Test
    void caption2201_is400NoReservation() {
        String caption = "ab".repeat(1100) + "c";

        assertThatThrownBy(() -> service.importPost(USER, caption, List.of()))
                .isInstanceOf(PostImportRequestException.class).hasMessage(DETAIL);
        assertNothingReservedOrCalled();
    }

    @Test
    void caption2200_isAccepted() {
        String caption = "ab".repeat(1100);
        recipeAi.then(goodReply());

        service.importPost(USER, caption, List.of());

        assertThat(recipeAi.requests.get(0).userText()).contains(caption);
    }

    @Test
    void crlfCaption_countsLikeTheBrowser() {
        String withLf = ("y".repeat(9) + "\n").repeat(219) + "y".repeat(10);
        assertThat(withLf).hasSize(2200);
        String withCrLf = withLf.replace("\n", "\r\n");
        assertThat(withCrLf.length()).isGreaterThan(2200);
        recipeAi.then(goodReply());

        service.importPost(USER, withCrLf, List.of());

        assertThat(recipeAi.requests.get(0).userText()).contains(withLf).doesNotContain("\r");
    }

    @Test
    void threeScreenshots_isInvalidPostNoReservation() {
        assertThatThrownBy(() -> service.importPost(USER, CAPTION, screenshots(3)))
                .isInstanceOf(PostImportRequestException.class).hasMessage(DETAIL);
        assertNothingReservedOrCalled();
    }

    @Test
    void badJpeg_isInvalidPhotoNoReservation() {
        assertThatThrownBy(() -> service.importPost(USER, CAPTION, List.of(jpeg(), new byte[] {1, 2, 3})))
                .isInstanceOf(InvalidScanPhotoException.class);
        assertNothingReservedOrCalled();
    }

    // ---- preconditions ----

    @Test
    void noPreferences_is409() {
        when(preferences.findById(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.importPost(USER, CAPTION, List.of()))
                .isInstanceOf(NoPreferencesException.class);
        // Preferences come first: even an empty request is a 409.
        assertThatThrownBy(() -> service.importPost(USER, null, List.of()))
                .isInstanceOf(NoPreferencesException.class);
        assertNothingReservedOrCalled();
    }

    @Test
    void limit_is429WithNoAiCall() {
        when(state.reserveGeneration(any(), any(), anyInt())).thenReturn(OptionalInt.empty());

        assertThatThrownBy(() -> service.importPost(USER, CAPTION, screenshots(1)))
                .isInstanceOf(DailyLimitException.class);

        assertThat(recipeAi.requests).isEmpty();
        assertThat(visionAi.requests).isEmpty();
        assertThat(postAi.requests).isEmpty();
        verify(state, never()).refundGeneration(any(), any());
        verifyNoInteractions(recipes);
    }

    // ---- the saved row ----

    @Test
    @SuppressWarnings("unchecked")
    void savedRow_isPostKindWithOriginSwapsAndCountsOnly() {
        visionAi.then(READING);
        postAi.then(goodReply());

        GenerateResultDto result = service.importPost(USER, CAPTION, screenshots(1));

        assertThat(result.remainingToday()).isEqualTo(7); // limit 10, the reservation made the count 3
        assertThat(result.recipe().origin()).isEqualTo("recipe");
        assertThat(result.recipe().swaps()).containsExactly(new StoredRecipe.Swap("butter", "olive oil"));

        GeneratedRecipe row = savedRow();
        assertThat(row.getKind()).isEqualTo("post");
        assertThat(row.getMealType()).isNull();
        assertThat(row.getTitle()).isEqualTo("Charred Corn and Feta Salad");
        assertThat(row.getProvider()).isEqualTo("groq");
        assertThat(row.getModel()).isEqualTo("test-model");
        assertThat(row.getNoveltyScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.getReferenceIds()).isEmpty();
        assertThat(row.getSavedAt()).isNull();

        Map<String, Object> post = (Map<String, Object>) row.getRecipe().get("post");
        assertThat(post).containsEntry("origin", "recipe")
                .containsEntry("swaps", List.of(Map.of("from", "butter", "to", "olive oil")));
        assertThat(row.getRequest().keySet()).containsExactlyInAnyOrder("screenshots", "caption");
        assertThat(row.getRequest()).containsEntry("screenshots", 1).containsEntry("caption", true);

        JsonMapper json = JsonMapper.builder().build();
        String documents = json.writeValueAsString(row.getRecipe()) + json.writeValueAsString(row.getRequest());
        assertThat(documents).doesNotContain(CAPTION).doesNotContain("grandmother")
                .doesNotContain("Golden corn kernels").doesNotContain("4 ears sweet corn")
                .doesNotContain("Grill the cobs");

        verify(state).reserveGeneration(USER, TODAY, 10);
        verify(state, never()).refundGeneration(any(), any());
        verify(transactionManager, times(1)).getTransaction(any());
    }

    // ---- logging ----

    @Test
    void log_holdsCountsAndReasonsOnly() {
        Logger logger = (Logger) LoggerFactory.getLogger(PostImportService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        logger.setLevel(Level.INFO);
        appender.start();
        logger.addAppender(appender);
        try {
            visionAi.then(READING);
            postAi.then(NOT_JSON, NOT_JSON, NOT_JSON);

            assertThatThrownBy(() -> service.importPost(USER, CAPTION, screenshots(1)))
                    .isInstanceOf(GenerationFailedException.class);

            assertThat(appender.list)
                    .filteredOn(e -> e.getFormattedMessage().startsWith("post import "))
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.getLevel()).isEqualTo(Level.INFO);
                        assertThat(e.getFormattedMessage())
                                .startsWith("post import screenshots=1 caption=true outcome=failed")
                                .contains("GenerationFailedException")
                                .contains("unreadable reply");
                    });
            for (ILoggingEvent e : appender.list) {
                assertThat(e.getFormattedMessage()).doesNotContain(USER.toString()).doesNotContain("grandmother")
                        .doesNotContain("Charred Corn Salad").doesNotContain("Golden corn");
            }
        } finally {
            logger.detachAppender(appender);
        }
    }
}
