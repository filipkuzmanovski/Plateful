package mk.ukim.finki.wp.recipeappbackend.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;
import mk.ukim.finki.wp.recipeappbackend.ai.AiNotConfiguredException;
import mk.ukim.finki.wp.recipeappbackend.ai.AiQuotaExceededException;
import mk.ukim.finki.wp.recipeappbackend.ai.AiReply;
import mk.ukim.finki.wp.recipeappbackend.ai.AiRequest;
import mk.ukim.finki.wp.recipeappbackend.ai.AiUnavailableException;
import mk.ukim.finki.wp.recipeappbackend.ai.RecipeAi;
import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.exception.NoPreferencesException;
import mk.ukim.finki.wp.recipeappbackend.food.FoodRules;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.DailyMealsDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.GeneratedRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.model.entities.UserPreferences;
import mk.ukim.finki.wp.recipeappbackend.model.projection.DailySetRow;
import mk.ukim.finki.wp.recipeappbackend.repository.AiStateRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.GeneratedRecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.PlatefulComparisonRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.ReferenceDataRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserPreferencesRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DailyMealsServiceTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 31);
    private static final LocalDate EARLIER = LocalDate.of(2026, 10, 28);
    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
    private static final UUID B = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    private static final UUID L = UUID.fromString("b0000000-0000-0000-0000-000000000002");
    private static final UUID D = UUID.fromString("b0000000-0000-0000-0000-000000000003");

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
    private PlatformTransactionManager transactionManager;
    private MovingClock clock;
    private ScriptedAi ai;
    private GeneratedRecipeMapper mapper;
    private DailyMealsService service;

    @BeforeEach
    void setUp() {
        preferences = mock(UserPreferencesRepository.class);
        users = mock(UserRepository.class);
        recipes = mock(GeneratedRecipeRepository.class);
        state = mock(AiStateRepository.class);
        ReferenceDataRepository reference = mock(ReferenceDataRepository.class);
        PlatefulComparisonRepository plateful = mock(PlatefulComparisonRepository.class);
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
        // A fresh day: nothing stored yet, and this caller wins the claim.
        when(state.findDailySet(USER, DATE)).thenReturn(Optional.empty());
        when(state.claimDailySet(USER, DATE)).thenReturn(true);
        when(state.finishDailySet(eq(USER), eq(DATE), any(), any(), any())).thenReturn(true);
        when(state.failDailySet(USER, DATE)).thenReturn(true);
        when(state.latestReadyBefore(USER, DATE)).thenReturn(Optional.empty());

        FoodRules rules = new FoodRules();
        PantryStaples staples = new PantryStaples();
        SafetyCheck safety = new SafetyCheck(rules, staples);
        mapper = new GeneratedRecipeMapper(rules);
        service = new DailyMealsService(
                preferences, users, recipes, state,
                new ReferenceFinder(reference, plateful, safety),
                new PromptBuilder(rules, staples, new Random(1)),
                ai, new DraftParser(new CuisineCatalog()), safety, new NoveltyCheck(staples),
                mapper, new TransactionTemplate(transactionManager), clock);
    }

    // ---- helpers

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

    private static final String BREAKFAST = recipeJson("Berry Oat Bowl", "oats", "blueberries", "honey");
    private static final String LUNCH = recipeJson("Lemon Chickpea Salad", "chickpeas", "lemon", "cucumber");
    private static final String DINNER = recipeJson("Garlic Mushroom Risotto", "rice", "mushrooms", "garlic");

    private static String meals(String breakfast, String lunch, String dinner) {
        StringBuilder json = new StringBuilder("{");
        if (breakfast != null) {
            json.append("\"breakfast\":").append(breakfast);
        }
        if (lunch != null) {
            json.append(json.length() > 1 ? "," : "").append("\"lunch\":").append(lunch);
        }
        if (dinner != null) {
            json.append(json.length() > 1 ? "," : "").append("\"dinner\":").append(dinner);
        }
        return json.append('}').toString();
    }

    private GeneratedRecipe stored(UUID id, String meal, String title) {
        RecipeDraft draft = new DraftParser(new CuisineCatalog())
                .parse(recipeJson(title, "rice", "beans", "salt")).draft();
        return GeneratedRecipe.builder().id(id).kind("daily").mealType(meal).title(title)
                .recipe(mapper.toDocument(mapper.fromDraft(draft, java.util.Set.of()))).build();
    }

    private void storeSet(LocalDate date, String status, boolean withRecipes) {
        UUID b = withRecipes ? B : null;
        UUID l = withRecipes ? L : null;
        UUID d = withRecipes ? D : null;
        DailySetRow row = new DailySetRow(date, status, b, l, d, OffsetDateTime.parse("2026-10-31T11:00:00Z"));
        if (date.equals(DATE)) {
            when(state.findDailySet(USER, DATE)).thenReturn(Optional.of(row));
        } else {
            when(state.latestReadyBefore(USER, DATE)).thenReturn(Optional.of(row));
        }
        if (withRecipes) {
            when(recipes.findAllById(List.of(B, L, D))).thenReturn(List.of(
                    stored(B, "breakfast", "Stored Breakfast"),
                    stored(L, "lunch", "Stored Lunch"),
                    stored(D, "dinner", "Stored Dinner")));
        }
    }

    private List<GeneratedRecipe> savedRows() {
        ArgumentCaptor<GeneratedRecipe> captor = ArgumentCaptor.forClass(GeneratedRecipe.class);
        verify(recipes, times(3)).save(captor.capture());
        return captor.getAllValues();
    }

    @SuppressWarnings("unchecked")
    private static List<String> schemaMeals(AiRequest request) {
        return new ArrayList<>(((Map<String, Object>) request.jsonSchema().get("properties")).keySet());
    }

    // ---- serving

    @Test
    void ready_isServedWithoutAiCall() {
        storeSet(DATE, "ready", true);

        DailyMealsDto result = service.today(USER, DATE);

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.date()).isEqualTo(DATE);
        assertThat(result.fresh()).isTrue();
        assertThat(result.meals()).containsOnlyKeys("breakfast", "lunch", "dinner");
        assertThat(result.meals().get("breakfast").title()).isEqualTo("Stored Breakfast");
        assertThat(result.meals().get("lunch").title()).isEqualTo("Stored Lunch");
        assertThat(result.meals().get("dinner").title()).isEqualTo("Stored Dinner");
        assertThat(ai.requests).isEmpty();
        verify(state, never()).claimDailySet(any(), any());
        verify(recipes, never()).save(any());
    }

    @Test
    void claimedByOther_returnsGenerating() {
        when(state.claimDailySet(USER, DATE)).thenReturn(false);
        storeSet(DATE, "generating", false);

        DailyMealsDto result = service.today(USER, DATE);

        assertThat(result.status()).isEqualTo("generating");
        assertThat(result.date()).isNull();
        assertThat(result.fresh()).isNull();
        assertThat(result.meals()).isNull();
        assertThat(ai.requests).isEmpty();
        verify(state, never()).failDailySet(any(), any());
    }

    @Test
    void lostRaceForTheClaim_servesTheReadySetTheWinnerStored() {
        // Nothing stored when first read, a ready set by the time the claim is refused.
        DailySetRow ready = new DailySetRow(DATE, "ready", B, L, D, OffsetDateTime.parse("2026-10-31T11:59:00Z"));
        when(state.findDailySet(USER, DATE)).thenReturn(Optional.empty(), Optional.of(ready));
        when(state.claimDailySet(USER, DATE)).thenReturn(false);
        when(recipes.findAllById(List.of(B, L, D))).thenReturn(List.of(
                stored(B, "breakfast", "Stored Breakfast"), stored(L, "lunch", "Stored Lunch"),
                stored(D, "dinner", "Stored Dinner")));

        DailyMealsDto result = service.today(USER, DATE);

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.fresh()).isTrue();
        assertThat(ai.requests).isEmpty();
    }

    @Test
    void failedCoolingDown_returnsFallback_noAiCall() {
        when(state.claimDailySet(USER, DATE)).thenReturn(false);
        storeSet(DATE, "failed", false);
        storeSet(EARLIER, "ready", true);

        DailyMealsDto result = service.today(USER, DATE);

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.date()).isEqualTo(EARLIER);
        assertThat(result.fresh()).isFalse();
        assertThat(result.meals()).containsOnlyKeys("breakfast", "lunch", "dinner");
        assertThat(ai.requests).isEmpty();
        verify(state, never()).failDailySet(any(), any());
    }

    @Test
    void noPreferences_is409() {
        when(preferences.findById(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.today(USER, DATE)).isInstanceOf(NoPreferencesException.class);

        verify(state, never()).claimDailySet(any(), any());
        assertThat(ai.requests).isEmpty();
    }

    // ---- generating

    @Test
    void allPass_savesThreeDailyRowsAndFinishes() {
        ai.then(meals(BREAKFAST, LUNCH, DINNER));

        DailyMealsDto result = service.today(USER, DATE);

        assertThat(ai.requests).hasSize(1);
        assertThat(schemaMeals(ai.requests.get(0))).containsExactly("breakfast", "lunch", "dinner");

        List<GeneratedRecipe> rows = savedRows();
        assertThat(rows).extracting(GeneratedRecipe::getKind).containsOnly("daily");
        assertThat(rows).extracting(GeneratedRecipe::getMealType).containsExactly("breakfast", "lunch", "dinner");
        assertThat(rows).extracting(GeneratedRecipe::getTitle)
                .containsExactly("Berry Oat Bowl", "Lemon Chickpea Salad", "Garlic Mushroom Risotto");
        assertThat(rows).extracting(GeneratedRecipe::getProvider).containsOnly("groq");
        assertThat(rows).extracting(GeneratedRecipe::getModel).containsOnly("test-model");
        assertThat(rows.get(0).getRequest()).containsOnlyKeys("mealDate").containsEntry("mealDate", "2026-10-31");
        assertThat(rows.get(0).getSavedAt()).isNull();

        // The rows are flushed before the set is updated, and both happen in one transaction.
        verify(recipes).flush();
        verify(state).finishDailySet(USER, DATE, rows.get(0).getId(), rows.get(1).getId(), rows.get(2).getId());
        verify(transactionManager, times(1)).getTransaction(any());
        verify(state, never()).failDailySet(any(), any());

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.date()).isEqualTo(DATE);
        assertThat(result.fresh()).isTrue();
        assertThat(result.meals().get("breakfast").id()).isEqualTo(rows.get(0).getId());
        assertThat(result.meals().get("lunch").title()).isEqualTo("Lemon Chickpea Salad");
        assertThat(result.meals().get("dinner").mealType()).isEqualTo("dinner");
        assertThat(result.meals().get("dinner").totalMinutes()).isEqualTo(30);
    }

    @Test
    void recentTitlesAreOfTheLastSevenDays() {
        when(recipes.recentDailyTitles(any(), any())).thenReturn(List.of("Yesterday's Stew"));
        ai.then(meals(BREAKFAST, LUNCH, DINNER));

        service.today(USER, DATE);

        verify(recipes).recentDailyTitles(USER, OffsetDateTime.parse("2026-10-24T12:00:00Z"));
        assertThat(ai.requests.get(0).userText()).contains("Yesterday's Stew");
    }

    @Test
    void oneMealFails_secondCallAsksOnlyForIt() {
        when(preferences.findById(USER)).thenReturn(Optional.of(
                UserPreferences.builder().userId(USER).allergens(List.of("peanuts")).build()));
        String peanutLunch = recipeJson("Peanut Noodles", "noodles", "peanut butter", "lime");
        ai.then(meals(BREAKFAST, peanutLunch, DINNER),
                meals(null, recipeJson("Lime Noodle Bowl", "noodles", "lime", "cilantro"), null));

        DailyMealsDto result = service.today(USER, DATE);

        assertThat(ai.requests).hasSize(2);
        AiRequest second = ai.requests.get(1);
        assertThat(schemaMeals(second)).containsExactly("lunch");
        assertThat(second.userText()).contains("lunch").contains("peanut butter")
                .doesNotContain("breakfast").doesNotContain("dinner");

        // Breakfast and dinner from call 1 are kept, lunch comes from call 2.
        assertThat(savedRows()).extracting(GeneratedRecipe::getTitle)
                .containsExactly("Berry Oat Bowl", "Lime Noodle Bowl", "Garlic Mushroom Risotto");
        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.fresh()).isTrue();
        verify(state, never()).failDailySet(any(), any());
    }

    @Test
    void aMealMissingFromTheReply_isAskedForAgain() {
        ai.then(meals(BREAKFAST, LUNCH, null), meals(null, null, DINNER));

        DailyMealsDto result = service.today(USER, DATE);

        assertThat(schemaMeals(ai.requests.get(1))).containsExactly("dinner");
        assertThat(result.meals().get("dinner").title()).isEqualTo("Garlic Mushroom Risotto");
    }

    @Test
    void stillFailing_marksFailedAndReturnsFallback() {
        String broken = "{\"title\":\"No ingredients\"}";
        ai.then(meals(BREAKFAST, broken, DINNER), meals(null, broken, null));
        storeSet(EARLIER, "ready", true);

        DailyMealsDto result = service.today(USER, DATE);

        assertThat(ai.requests).hasSize(2); // exactly one retry, never more
        verify(recipes, never()).save(any());
        verify(state, never()).finishDailySet(any(), any(), any(), any(), any());
        verify(state).failDailySet(USER, DATE);
        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.date()).isEqualTo(EARLIER);
        assertThat(result.fresh()).isFalse();
    }

    static Stream<RuntimeException> aiErrors() {
        return Stream.of(new AiQuotaExceededException("quota"), new AiUnavailableException("down"),
                new AiNotConfiguredException("no key"));
    }

    @ParameterizedTest
    @MethodSource("aiErrors")
    void aiError_marksFailedAndReturnsFallback(RuntimeException error) {
        ai.then(error);
        storeSet(EARLIER, "ready", true);

        DailyMealsDto result = service.today(USER, DATE);

        verify(state).failDailySet(USER, DATE);
        verify(recipes, never()).save(any());
        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.date()).isEqualTo(EARLIER);
        assertThat(result.fresh()).isFalse();
    }

    @Test
    void quota_onTheSecondCall_alsoMarksFailedAndReturnsFallback() {
        ai.then(meals(BREAKFAST, null, DINNER), new AiQuotaExceededException("quota"));

        DailyMealsDto result = service.today(USER, DATE);

        verify(state).failDailySet(USER, DATE);
        verify(recipes, never()).save(any());
        assertThat(result.status()).isEqualTo("unavailable");
    }

    @Test
    void noEarlierSet_returnsUnavailable() {
        ai.then(new AiUnavailableException("down"));

        DailyMealsDto result = service.today(USER, DATE);

        assertThat(result.status()).isEqualTo("unavailable");
        assertThat(result.date()).isNull();
        assertThat(result.fresh()).isNull();
        assertThat(result.meals()).isNull();
        verify(state).latestReadyBefore(USER, DATE);
    }

    @Test
    void failDailySetReturningFalse_withNoReadySetYet_returnsTheFallback() {
        when(state.failDailySet(USER, DATE)).thenReturn(false);
        ai.then(new AiQuotaExceededException("quota"));
        storeSet(EARLIER, "ready", true);

        DailyMealsDto result = service.today(USER, DATE);

        assertThat(result.fresh()).isFalse();
        assertThat(result.date()).isEqualTo(EARLIER);
    }

    @Test
    void failDailySetReturningFalse_servesTheSetTheNewOwnerFinished() {
        when(state.failDailySet(USER, DATE)).thenReturn(false);
        DailySetRow ready = new DailySetRow(DATE, "ready", B, L, D, OffsetDateTime.parse("2026-10-31T12:01:00Z"));
        when(state.findDailySet(USER, DATE)).thenReturn(Optional.empty(), Optional.of(ready));
        when(recipes.findAllById(List.of(B, L, D))).thenReturn(List.of(
                stored(B, "breakfast", "Their Breakfast"), stored(L, "lunch", "Their Lunch"),
                stored(D, "dinner", "Their Dinner")));
        ai.then(new AiQuotaExceededException("quota"));

        DailyMealsDto result = service.today(USER, DATE);

        assertThat(result.date()).isEqualTo(DATE);
        assertThat(result.fresh()).isTrue();
        assertThat(result.meals().get("lunch").title()).isEqualTo("Their Lunch");
    }

    @Test
    void slowFirstCall_withAFailedMeal_makesNoSecondCall() {
        String broken = "{\"title\":\"No ingredients\"}";
        ai.perCall = Duration.ofSeconds(50); // 25 s left of the 75 s budget: under the 30 s minimum
        ai.then(meals(BREAKFAST, broken, DINNER), meals(null, LUNCH, null));
        storeSet(EARLIER, "ready", true);

        DailyMealsDto result = service.today(USER, DATE);

        assertThat(ai.requests).hasSize(1);
        verify(recipes, never()).save(any());
        verify(state).failDailySet(USER, DATE);
        assertThat(result.date()).isEqualTo(EARLIER);
        assertThat(result.fresh()).isFalse();
    }

    @Test
    void firstCallWithExactlyTheMinimumLeft_stillRetries() {
        String broken = "{\"title\":\"No ingredients\"}";
        ai.perCall = Duration.ofSeconds(45); // exactly 30 s left: allowed
        ai.then(meals(BREAKFAST, broken, DINNER), meals(null, LUNCH, null));

        DailyMealsDto result = service.today(USER, DATE);

        assertThat(ai.requests).hasSize(2);
        assertThat(result.status()).isEqualTo("ready");
    }

    @Test
    void databaseErrorWhileSaving_marksFailedAndRethrows() {
        when(recipes.save(any(GeneratedRecipe.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        ai.then(meals(BREAKFAST, LUNCH, DINNER));

        assertThatThrownBy(() -> service.today(USER, DATE))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);

        verify(transactionManager).rollback(any());
        verify(state, never()).finishDailySet(any(), any(), any(), any(), any());
        verify(state).failDailySet(USER, DATE);
    }

    @Test
    void staleProfileKey_marksFailedAndPropagates() {
        when(preferences.findById(USER)).thenReturn(Optional.of(
                UserPreferences.builder().userId(USER).allergens(List.of("no_such_allergen")).build()));
        ai.then(meals(BREAKFAST, LUNCH, DINNER));

        assertThatThrownBy(() -> service.today(USER, DATE)).isInstanceOf(IllegalArgumentException.class);

        verify(state).failDailySet(USER, DATE);
        assertThat(ai.requests).isEmpty(); // the stale key stops it before any AI call
        verify(recipes, never()).save(any());
    }

    @Test
    void claimTakenOverDuringGeneration_rollsBackAndServesTheCurrentRow() {
        when(state.finishDailySet(eq(USER), eq(DATE), any(), any(), any())).thenReturn(false);
        DailySetRow ready = new DailySetRow(DATE, "ready", B, L, D, OffsetDateTime.parse("2026-10-31T12:00:30Z"));
        when(state.findDailySet(USER, DATE)).thenReturn(Optional.empty(), Optional.of(ready));
        when(recipes.findAllById(List.of(B, L, D))).thenReturn(List.of(
                stored(B, "breakfast", "Their Breakfast"), stored(L, "lunch", "Their Lunch"),
                stored(D, "dinner", "Their Dinner")));
        ai.then(meals(BREAKFAST, LUNCH, DINNER));

        DailyMealsDto result = service.today(USER, DATE);

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        verify(state, never()).failDailySet(any(), any());
        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.meals().get("breakfast").title()).isEqualTo("Their Breakfast");
    }

    @Test
    void claimTakenOverDuringGeneration_stillGenerating_returnsGenerating() {
        when(state.finishDailySet(eq(USER), eq(DATE), any(), any(), any())).thenReturn(false);
        DailySetRow generating = new DailySetRow(DATE, "generating", null, null, null,
                OffsetDateTime.parse("2026-10-31T12:00:30Z"));
        when(state.findDailySet(USER, DATE)).thenReturn(Optional.empty(), Optional.of(generating));
        ai.then(meals(BREAKFAST, LUNCH, DINNER));

        DailyMealsDto result = service.today(USER, DATE);

        verify(transactionManager).rollback(any());
        assertThat(result.status()).isEqualTo("generating");
    }

    @Test
    void doesNotTouchUsageCounter() {
        ai.then(meals(BREAKFAST, LUNCH, DINNER));

        service.today(USER, DATE);

        verify(state, never()).reserveGeneration(any(), any(), anyInt());
        verify(state, never()).refundGeneration(any(), any());
        verify(state, never()).generationsUsed(any(), any());
    }
}
