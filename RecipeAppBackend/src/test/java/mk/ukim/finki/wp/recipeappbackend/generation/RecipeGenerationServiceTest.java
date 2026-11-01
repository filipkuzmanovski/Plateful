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
import java.util.stream.IntStream;
import mk.ukim.finki.wp.recipeappbackend.ai.AiNotConfiguredException;
import mk.ukim.finki.wp.recipeappbackend.ai.AiProperties;
import mk.ukim.finki.wp.recipeappbackend.ai.AiQuotaExceededException;
import mk.ukim.finki.wp.recipeappbackend.ai.AiReply;
import mk.ukim.finki.wp.recipeappbackend.ai.AiRequest;
import mk.ukim.finki.wp.recipeappbackend.ai.AiUnavailableException;
import mk.ukim.finki.wp.recipeappbackend.ai.RecipeAi;
import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.exception.DailyLimitException;
import mk.ukim.finki.wp.recipeappbackend.exception.GenerationFailedException;
import mk.ukim.finki.wp.recipeappbackend.exception.GenerationRequestException;
import mk.ukim.finki.wp.recipeappbackend.exception.NoPreferencesException;
import mk.ukim.finki.wp.recipeappbackend.food.FoodRules;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.GenerateRequestDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GenerateResultDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.GeneratedRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.model.entities.UserPreferences;
import mk.ukim.finki.wp.recipeappbackend.model.projection.ReferenceRecipe;
import mk.ukim.finki.wp.recipeappbackend.repository.AiStateRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.GeneratedRecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.PlatefulComparisonRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.ReferenceDataRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserPreferencesRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RecipeGenerationServiceTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
    private static final String NOT_JSON = "this is not json";

    /** A clock the fake AI moves forward on every call. */
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
    private ReferenceDataRepository reference;
    private PlatefulComparisonRepository plateful;
    private PlatformTransactionManager transactionManager;
    private MovingClock clock;
    private ScriptedAi ai;
    private RecipeGenerationService service;

    @BeforeEach
    void setUp() {
        preferences = mock(UserPreferencesRepository.class);
        users = mock(UserRepository.class);
        recipes = mock(GeneratedRecipeRepository.class);
        state = mock(AiStateRepository.class);
        reference = mock(ReferenceDataRepository.class);
        plateful = mock(PlatefulComparisonRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        clock = new MovingClock();
        ai = new ScriptedAi(clock);

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
        SafetyCheck safety = new SafetyCheck(rules, staples);
        service = new RecipeGenerationService(
                preferences, users, recipes,
                new AiUsageService(state, new AiProperties(null, null, null, 10), clock),
                new ReferenceFinder(reference, plateful, safety),
                new PromptBuilder(rules, staples, new Random(1)),
                ai, new DraftParser(new CuisineCatalog()), safety, new NoveltyCheck(staples),
                new GeneratedRecipeMapper(rules), new TransactionTemplate(transactionManager), 10, clock);
    }

    private static GenerateRequestDto request(String mealType, boolean only, String... ingredients) {
        return new GenerateRequestDto(List.of(ingredients), mealType, only);
    }

    private static String recipeJson(String title, String... ingredientNames) {
        StringBuilder ingredients = new StringBuilder();
        for (String name : ingredientNames) {
            if (ingredients.length() > 0) {
                ingredients.append(',');
            }
            ingredients.append("{\"name\":\"").append(name).append("\",\"amount\":\"1 portion\"}");
        }
        return "{\"title\":\"" + title + "\",\"description\":\"A tasty dish.\",\"servings\":2,"
                + "\"prepMinutes\":10,\"cookMinutes\":20,\"cuisine\":null,\"ingredients\":[" + ingredients
                + "],\"steps\":[\"Prepare everything.\",\"Cook gently until done.\"]}";
    }

    private static ReferenceRecipe example(long id, String title, String... names) {
        return new ReferenceRecipe(id, title, List.of(names), List.of(names), List.of("Cook it."),
                List.of("dinner"), 1);
    }

    private GeneratedRecipe savedRow() {
        ArgumentCaptor<GeneratedRecipe> captor = ArgumentCaptor.forClass(GeneratedRecipe.class);
        verify(recipes).save(captor.capture());
        return captor.getValue();
    }

    private void withPreferences(UserPreferences p) {
        when(preferences.findById(USER)).thenReturn(Optional.of(p));
    }

    // ---- the request ----

    @Test
    void noPreferences_is409() {
        when(preferences.findById(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(USER, request(null, false, "tomato", "basil")))
                .isInstanceOf(NoPreferencesException.class);
        verifyNoInteractions(state, recipes);
        assertThat(ai.requests).isEmpty();
    }

    @Test
    void tooFewOrTooMany_is400() {
        List<String> sixteen = IntStream.range(0, 16).mapToObj(i -> "ingredient " + (char) ('a' + i)).toList();

        assertThatThrownBy(() -> service.generate(USER, request(null, false, "tomato")))
                .isInstanceOf(GenerationRequestException.class);
        // "Tomatoes" and "tomato" are the same ingredient.
        assertThatThrownBy(() -> service.generate(USER, request(null, false, "Tomatoes", "tomato")))
                .isInstanceOf(GenerationRequestException.class);
        // Names that normalise to nothing do not count.
        assertThatThrownBy(() -> service.generate(USER, request(null, false, "tomato", "123", "!!")))
                .isInstanceOf(GenerationRequestException.class);
        assertThatThrownBy(() -> service.generate(USER, new GenerateRequestDto(sixteen, null, false)))
                .isInstanceOf(GenerationRequestException.class);
        verifyNoInteractions(state, recipes);
        assertThat(ai.requests).isEmpty();
    }

    @Test
    void badMealType_is400() {
        assertThatThrownBy(() -> service.generate(USER, request("brunch", false, "tomato", "basil")))
                .isInstanceOf(GenerationRequestException.class)
                .hasMessageContaining("breakfast, lunch or dinner");
        verifyNoInteractions(state, recipes);
    }

    @Test
    void conflict_isRejectedBeforeReserving() {
        withPreferences(UserPreferences.builder().userId(USER).allergens(List.of("peanuts")).build());

        assertThatThrownBy(() -> service.generate(USER, request(null, false, "Peanut butter", "bread")))
                .isInstanceOfSatisfying(GenerationRequestException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("Some ingredients conflict with your food preferences.");
                    // The raw request string, exactly as sent.
                    assertThat(e.getConflicts())
                            .containsExactly(new SafetyCheck.Conflict("Peanut butter", "allergy: Peanuts"));
                });
        verifyNoInteractions(state, recipes);
        assertThat(ai.requests).isEmpty();
    }

    @Test
    void staleProfileKey_failsClosed_neverReservesOrCallsTheAi() {
        withPreferences(UserPreferences.builder().userId(USER).allergens(List.of("no_such_allergen")).build());

        assertThatThrownBy(() -> service.generate(USER, request(null, false, "tomato", "basil")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(state, recipes);
        assertThat(ai.requests).isEmpty();
    }

    @Test
    void limitReached_is429_noAiCall() {
        when(state.reserveGeneration(any(), any(), anyInt())).thenReturn(OptionalInt.empty());

        assertThatThrownBy(() -> service.generate(USER, request(null, false, "tomato", "basil")))
                .isInstanceOf(DailyLimitException.class);
        assertThat(ai.requests).isEmpty();
        verify(state, never()).refundGeneration(any(), any());
        verifyNoInteractions(recipes);
    }

    // ---- the generation ----

    @Test
    void passFirstTime_savesCookRowAndReturnsRemaining() {
        // Shares only tomato with the draft's {tomato, fresh basil, garlic}: 1 of 5 = 0.2.
        when(reference.findClosest(any())).thenReturn(List.of(example(42, "Pasta Pomodoro", "tomato", "cheese", "pasta")));
        ai.then(recipeJson("Garlic Basil Tomato Skillet", "Tomatoes", "fresh basil", "garlic", "olive oil", "salt"));

        GenerateResultDto result = service.generate(USER, request("dinner", false, "Tomato", "basil"));

        assertThat(result.remainingToday()).isEqualTo(7); // limit 10, the reservation made the count 3
        assertThat(result.recipe().title()).isEqualTo("Garlic Basil Tomato Skillet");
        assertThat(result.recipe().kind()).isEqualTo("cook");
        assertThat(result.recipe().saved()).isFalse();
        assertThat(result.recipe().provider()).isEqualTo("groq");
        assertThat(result.recipe().ingredients()).extracting("name", "extra")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Tomatoes", false),
                        org.assertj.core.groups.Tuple.tuple("fresh basil", false),
                        org.assertj.core.groups.Tuple.tuple("garlic", true),
                        org.assertj.core.groups.Tuple.tuple("olive oil", false),
                        org.assertj.core.groups.Tuple.tuple("salt", false));
        assertThat(result.recipe().diets().vegan()).isTrue();
        assertThat(result.recipe().allergens()).isEmpty();

        GeneratedRecipe row = savedRow();
        assertThat(row.getKind()).isEqualTo("cook");
        assertThat(row.getMealType()).isEqualTo("dinner");
        assertThat(row.getTitle()).isEqualTo("Garlic Basil Tomato Skillet");
        assertThat(row.getProvider()).isEqualTo("groq");
        assertThat(row.getModel()).isEqualTo("test-model");
        assertThat(row.getNoveltyScore()).isEqualByComparingTo(new BigDecimal("0.200"));
        assertThat(row.getNoveltyScore().scale()).isEqualTo(3);
        assertThat(row.getReferenceIds()).containsExactly(42L);
        assertThat(row.getSavedAt()).isNull();
        assertThat(row.getRequest()).containsEntry("ingredients", List.of("tomato", "basil"))
                .containsEntry("mealType", "dinner").containsEntry("onlyWhatIHave", false);
        assertThat(row.getRecipe()).containsEntry("title", "Garlic Basil Tomato Skillet");
        assertThat((List<?>) row.getRecipe().get("ingredients")).hasSize(5);
        assertThat(ai.requests).hasSize(1);
        // Reserved once, for today (UTC), against the limit; no refund.
        verify(state).reserveGeneration(USER, LocalDate.of(2026, 10, 31), 10);
        verify(state, never()).refundGeneration(any(), any());
        // The insert ran in one short transaction of its own.
        verify(transactionManager, times(1)).getTransaction(any());
    }

    @Test
    void noMealType_isStoredAsNull() {
        ai.then(recipeJson("Basil Tomato Skillet", "tomato", "basil", "olive oil"));

        service.generate(USER, request(null, false, "tomato", "basil"));

        assertThat(savedRow().getMealType()).isNull();
    }

    @Test
    void noveltyFail_retriesWithReason() {
        when(reference.findClosest(any())).thenReturn(
                List.of(example(7, "Tomato Basil Garlic Pasta", "tomato", "basil", "garlic")));
        ai.then(recipeJson("Garlic Basil Tomato Skillet", "tomato", "basil", "garlic", "olive oil"),
                recipeJson("Basil Tomato Onion Stew", "tomato", "basil", "onion", "olive oil"));

        GenerateResultDto result = service.generate(USER, request(null, false, "tomato", "basil"));

        assertThat(result.recipe().title()).isEqualTo("Basil Tomato Onion Stew");
        assertThat(ai.requests).hasSize(2);
        assertThat(ai.requests.get(0).userText()).doesNotContain("Too similar");
        assertThat(ai.requests.get(1).userText())
                .contains(PromptBuilder.RETRY_HEADING)
                .contains("Too similar to 'Tomato Basil Garlic Pasta'");
        verify(state, never()).refundGeneration(any(), any());
    }

    @Test
    void safetyFail_retriesWithReason() {
        ai.then(recipeJson("Garlic Basil Tomato Skillet", "tomato", "basil", "garlic", "olive oil"),
                recipeJson("Basil Tomato Skillet", "tomato", "basil", "olive oil"));

        service.generate(USER, request(null, true, "tomato", "basil"));

        assertThat(ai.requests).hasSize(2);
        assertThat(ai.requests.get(1).userText()).contains("not in the list: garlic");
    }

    @Test
    void malformedReply_retries() {
        ai.then(NOT_JSON, recipeJson("Basil Tomato Skillet", "tomato", "basil", "olive oil"));

        service.generate(USER, request(null, false, "tomato", "basil"));

        assertThat(ai.requests).hasSize(2);
        assertThat(ai.requests.get(1).userText()).contains(PromptBuilder.RETRY_HEADING).contains("unreadable reply");
        verify(recipes, times(1)).save(any());
        verify(state, never()).refundGeneration(any(), any());
    }

    @Test
    void threeFailures_is422_andRefunds() {
        ai.then(NOT_JSON, NOT_JSON, NOT_JSON);

        assertThatThrownBy(() -> service.generate(USER, request(null, false, "tomato", "basil")))
                .isInstanceOf(GenerationFailedException.class);

        assertThat(ai.requests).hasSize(3);
        verify(state).refundGeneration(USER, LocalDate.of(2026, 10, 31));
        verify(recipes, never()).save(any());
    }

    @Test
    void quota_is503_andRefunds() {
        ai.then(new AiQuotaExceededException("quota"));

        assertThatThrownBy(() -> service.generate(USER, request(null, false, "tomato", "basil")))
                .isInstanceOf(AiQuotaExceededException.class);

        assertThat(ai.requests).hasSize(1);
        verify(state).refundGeneration(USER, LocalDate.of(2026, 10, 31));
        verify(recipes, never()).save(any());
    }

    @Test
    void notConfigured_is503_andRefunds() {
        ai.then(new AiNotConfiguredException("no key"));

        assertThatThrownBy(() -> service.generate(USER, request(null, false, "tomato", "basil")))
                .isInstanceOf(AiNotConfiguredException.class);

        assertThat(ai.requests).hasSize(1);
        verify(state).refundGeneration(USER, LocalDate.of(2026, 10, 31));
    }

    @Test
    void unavailable_is503_andRefunds() {
        ai.then(new AiUnavailableException("timeout"));

        assertThatThrownBy(() -> service.generate(USER, request(null, false, "tomato", "basil")))
                .isInstanceOf(AiUnavailableException.class);

        assertThat(ai.requests).hasSize(1);
        verify(state).refundGeneration(USER, LocalDate.of(2026, 10, 31));
    }

    @Test
    void aiErrorOnALaterAttempt_stillEndsTheGenerationAndRefunds() {
        ai.then(NOT_JSON, new AiUnavailableException("timeout"));

        assertThatThrownBy(() -> service.generate(USER, request(null, false, "tomato", "basil")))
                .isInstanceOf(AiUnavailableException.class);

        assertThat(ai.requests).hasSize(2);
        verify(state).refundGeneration(USER, LocalDate.of(2026, 10, 31));
    }

    @Test
    void saveFails_refunds() {
        when(recipes.save(any(GeneratedRecipe.class))).thenThrow(new IllegalStateException("db down"));
        ai.then(recipeJson("Basil Tomato Skillet", "tomato", "basil", "olive oil"));

        assertThatThrownBy(() -> service.generate(USER, request(null, false, "tomato", "basil")))
                .isInstanceOf(IllegalStateException.class);

        verify(state).refundGeneration(USER, LocalDate.of(2026, 10, 31));
    }

    @Test
    void failedRefund_doesNotHideTheOriginalError() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(state).refundGeneration(any(), any());
        ai.then(new AiUnavailableException("timeout"));

        assertThatThrownBy(() -> service.generate(USER, request(null, false, "tomato", "basil")))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void budget_noNewAttemptUnder30sLeft() {
        // Call 1 ends at 25 s (50 s left: attempt 2 starts), call 2 ends at 50 s (25 s left: stop).
        ai.perCall = Duration.ofSeconds(25);
        ai.then(NOT_JSON, NOT_JSON, recipeJson("Basil Tomato Skillet", "tomato", "basil", "olive oil"));

        assertThatThrownBy(() -> service.generate(USER, request(null, false, "tomato", "basil")))
                .isInstanceOf(GenerationFailedException.class);

        assertThat(ai.requests).hasSize(2);
        verify(state).refundGeneration(USER, LocalDate.of(2026, 10, 31));
        verify(recipes, never()).save(any());
    }

    @Test
    void budget_exactly30sLeft_stillAllowsTheNextAttempt() {
        ai.perCall = Duration.ofSeconds(45);
        ai.then(NOT_JSON, recipeJson("Basil Tomato Skillet", "tomato", "basil", "olive oil"));

        service.generate(USER, request(null, false, "tomato", "basil"));

        assertThat(ai.requests).hasSize(2); // 75 - 45 = 30 s left is enough
    }

    @Test
    void zeroExamples_stillGenerates() {
        when(reference.findClosest(any())).thenReturn(List.of());
        ai.then(recipeJson("Basil Tomato Skillet", "tomato", "basil", "olive oil"));

        GenerateResultDto result = service.generate(USER, request(null, false, "tomato", "basil"));

        assertThat(result.recipe().title()).isEqualTo("Basil Tomato Skillet");
        assertThat(ai.requests.get(0).userText()).doesNotContain("Inspiration only");
        assertThat(savedRow().getReferenceIds()).isEmpty();
        assertThat(savedRow().getNoveltyScore()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void theProviderIsNeverToldAboutHealth() {
        withPreferences(UserPreferences.builder().userId(USER).allergens(List.of("peanuts"))
                .diets(List.of("vegan")).build());
        ai.then(recipeJson("Basil Tomato Skillet", "tomato", "basil", "olive oil"));

        service.generate(USER, request(null, false, "tomato", "basil"));

        AiRequest sent = ai.requests.get(0);
        assertThat(sent.userText().toLowerCase()).doesNotContain("allerg").doesNotContain("diet")
                .doesNotContain(USER.toString());
        assertThat(sent.userText()).contains("Never use these ingredients");
        assertThat(sent.temperature()).isEqualTo(1.0);
        assertThat(sent.maxOutputTokens()).isEqualTo(2048);
        assertThat((Map<?, ?>) sent.jsonSchema()).isNotEmpty();
    }

    @Test
    void threeFailures_logOneInfoLineWithTheReasons() {
        Logger logger = (Logger) LoggerFactory.getLogger(RecipeGenerationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        logger.setLevel(Level.INFO);
        appender.start();
        logger.addAppender(appender);
        try {
            ai.then(NOT_JSON, NOT_JSON, NOT_JSON);

            assertThatThrownBy(() -> service.generate(USER, request(null, false, "tomato", "basil")))
                    .isInstanceOf(GenerationFailedException.class);

            assertThat(appender.list)
                    .filteredOn(e -> e.getFormattedMessage().startsWith("AI generation failed"))
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.getLevel()).isEqualTo(Level.INFO);
                        assertThat(e.getFormattedMessage())
                                .startsWith("AI generation failed after 3 attempt(s): ")
                                .contains("unreadable reply")
                                .doesNotContain(USER.toString())
                                .doesNotContain("tomato").doesNotContain("basil");
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }
}
