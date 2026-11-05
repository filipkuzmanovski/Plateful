package mk.ukim.finki.wp.recipeappbackend.nutrition;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import mk.ukim.finki.wp.recipeappbackend.ai.AiNotConfiguredException;
import mk.ukim.finki.wp.recipeappbackend.ai.AiProperties;
import mk.ukim.finki.wp.recipeappbackend.ai.AiQuotaExceededException;
import mk.ukim.finki.wp.recipeappbackend.ai.AiUnavailableException;
import mk.ukim.finki.wp.recipeappbackend.generation.GeneratedRecipeMapper;
import mk.ukim.finki.wp.recipeappbackend.generation.StoredRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.GeneratedRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeIngredient;
import mk.ukim.finki.wp.recipeappbackend.repository.GeneratedRecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.NutritionEstimateRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeIngredientRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class NutritionWorkerTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private static final OffsetDateTime NOW_OFFSET = NOW.atOffset(ZoneOffset.UTC);
    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final NutritionTarget RECIPE_TARGET = new NutritionTarget(NutritionTarget.Kind.RECIPE, ID);
    private static final NutritionTarget GENERATED_TARGET = new NutritionTarget(NutritionTarget.Kind.GENERATED, ID);
    private static final NutritionTotals TOTALS = new NutritionTotals(400, 20, 10, 50);
    private static final List<NutritionItem> ITEMS = List.of(new NutritionItem(1, 100, 400, 20, 10, 50));
    private static final List<RecipeIngredient> OLD = List.of(ingredient("rice", "1 cup"));
    private static final List<RecipeIngredient> NEW = List.of(ingredient("rice", "2 cups"));

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

    private NutritionEstimator estimator;
    private NutritionEstimateRepository repo;
    private RecipeRepository recipes;
    private RecipeIngredientRepository ingredients;
    private GeneratedRecipeRepository generated;
    private GeneratedRecipeMapper mapper;
    private MovingClock clock;
    private NutritionWorker worker;

    @BeforeEach
    void setUp() {
        estimator = mock(NutritionEstimator.class);
        repo = mock(NutritionEstimateRepository.class);
        recipes = mock(RecipeRepository.class);
        ingredients = mock(RecipeIngredientRepository.class);
        generated = mock(GeneratedRecipeRepository.class);
        mapper = mock(GeneratedRecipeMapper.class);
        clock = new MovingClock();
        AiProperties properties = new AiProperties(null, null, null, 0, 0, null, null, null, 40);
        worker = new NutritionWorker(estimator, repo, recipes, ingredients, generated, mapper, properties,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), clock);

        Recipe recipe = Recipe.builder().id(ID).title("Soup").servings(4).build();
        when(repo.next(any())).thenReturn(Optional.of(RECIPE_TARGET));
        when(recipes.findById(ID)).thenReturn(Optional.of(recipe));
        when(recipes.findForReviewById(ID)).thenReturn(Optional.of(recipe));
        when(ingredients.findByRecipeIdOrderBySortOrderAsc(ID)).thenReturn(OLD);
        when(estimator.estimate(any(), any(), any()))
                .thenReturn(new NutritionEstimator.Ready(TOTALS, ITEMS, "groq", "openai/gpt-oss-120b"));
    }

    private static RecipeIngredient ingredient(String name, String text) {
        return RecipeIngredient.builder().ingredientName(name).originalText(text).build();
    }

    private static String hashOf(List<RecipeIngredient> list) {
        return NutritionLines.hash(NutritionLines.forRecipe(list));
    }

    private void advance(Duration d) {
        clock.advance(d);
    }

    private void generatedTarget() {
        GeneratedRecipe row = GeneratedRecipe.builder().id(ID).build();
        when(repo.next(any())).thenReturn(Optional.of(GENERATED_TARGET));
        when(generated.findById(ID)).thenReturn(Optional.of(row));
        when(mapper.read(row)).thenReturn(new StoredRecipe("Soup", null, 4, 0, 0, null,
                List.of(new StoredRecipe.StoredIngredient("rice", "1 cup", false)),
                List.of(), null, List.of(), null));
    }

    @Test
    void nothingToDo_callsNoAi() {
        when(repo.next(any())).thenReturn(Optional.empty());
        worker.run();
        verifyNoInteractions(estimator);
    }

    @Test
    void looksBackOneDay() {
        worker.run();
        verify(repo).next(NOW_OFFSET.minusHours(24));
    }

    @Test
    void ready_upload_isSavedWithTotalsBreakdownAndHash() {
        worker.run();
        verify(repo).saveReady(eq(RECIPE_TARGET), eq(TOTALS), argThat(json -> json.contains("\"line\":1")),
                eq(hashOf(OLD)), eq("groq"), eq("openai/gpt-oss-120b"));
    }

    @Test
    void ready_ai_isSaved_usingItsStoredLinesAndServings() {
        generatedTarget();
        worker.run();
        verify(estimator).estimate("Soup", 4, List.of("rice | 1 cup"));
        verify(repo).saveReady(eq(GENERATED_TARGET), eq(TOTALS), any(),
                eq(NutritionLines.hash(List.of("rice | 1 cup"))), eq("groq"), eq("openai/gpt-oss-120b"));
    }

    @Test
    void failed_storesAFailedRow() {
        when(estimator.estimate(any(), any(), any())).thenReturn(new NutritionEstimator.Failed("bad"));
        worker.run();
        verify(repo).saveFailed(RECIPE_TARGET, hashOf(OLD));
        verify(repo, never()).saveReady(any(), any(), any(), any(), any(), any());
    }

    @Test
    void totalsBelowTheColumnLimit_areSaved() {
        NutritionTotals big = new NutritionTotals(9_999_999.9, 9_999_999.9, 9_999_999.9, 9_999_999.9);
        when(estimator.estimate(any(), any(), any()))
                .thenReturn(new NutritionEstimator.Ready(big, ITEMS, "groq", "m"));
        worker.run();
        verify(repo).saveReady(eq(RECIPE_TARGET), eq(big), any(), any(), any(), any());
        verify(repo, never()).saveFailed(any(), any());
    }

    @ParameterizedTest
    @MethodSource("overflowingTotals")
    void totalsAtTheColumnLimit_areStoredAsFailed_notPaused(NutritionTotals total) {
        when(estimator.estimate(any(), any(), any()))
                .thenReturn(new NutritionEstimator.Ready(total, ITEMS, "groq", "m"));
        worker.run();
        worker.run();
        verify(repo, never()).saveReady(any(), any(), any(), any(), any(), any());
        verify(repo, times(2)).saveFailed(RECIPE_TARGET, hashOf(OLD));
    }

    static Stream<NutritionTotals> overflowingTotals() {
        double over = 10_000_000;
        return Stream.of(new NutritionTotals(over, 1, 1, 1), new NutritionTotals(1, over, 1, 1),
                new NutritionTotals(1, 1, over, 1), new NutritionTotals(1, 1, 1, over));
    }

    @Test
    void stopsAtTheDailyCap() {
        when(repo.createdSince(any())).thenReturn(40);
        worker.run();
        verify(repo, never()).next(any());
    }

    @Test
    void underTheCap_runs() {
        when(repo.createdSince(any())).thenReturn(39);
        worker.run();
        verify(repo).next(any());
    }

    @Test
    void capCountsFromTheStartOfTheUtcDay() {
        worker.run();
        verify(repo).createdSince(OffsetDateTime.parse("2026-11-05T00:00:00Z"));
    }

    static Stream<Arguments> providerErrors() {
        return Stream.of(
                Arguments.of(new AiQuotaExceededException("busy")),
                Arguments.of(new AiUnavailableException("down")),
                Arguments.of(new AiNotConfiguredException("none")),
                Arguments.of(new IllegalStateException("boom")));
    }

    @ParameterizedTest
    @MethodSource("providerErrors")
    void anErrorStoresNothingAndPausesFiveMinutes(RuntimeException error) {
        when(estimator.estimate(any(), any(), any())).thenThrow(error);
        assertThatCode(worker::run).doesNotThrowAnyException();
        verify(repo, never()).saveFailed(any(), any());
        advance(Duration.ofMinutes(5).minusSeconds(1));
        worker.run();
        verify(estimator, times(1)).estimate(any(), any(), any());
        advance(Duration.ofSeconds(1));
        worker.run();
        verify(estimator, times(2)).estimate(any(), any(), any());
    }

    @Test
    void ingredientsChangedDuringTheCall_discardsTheAnswer() {
        when(ingredients.findByRecipeIdOrderBySortOrderAsc(ID)).thenReturn(OLD, NEW);
        worker.run();
        verify(recipes).findForReviewById(ID);
        verify(repo, never()).saveReady(any(), any(), any(), any(), any(), any());
        verify(repo, never()).saveFailed(any(), any());
    }

    @Test
    void recipeDeletedDuringTheCall_storesNothing() {
        when(recipes.findForReviewById(ID)).thenReturn(Optional.empty());
        worker.run();
        verify(repo, never()).saveReady(any(), any(), any(), any(), any(), any());
        verify(repo, never()).saveFailed(any(), any());
    }

    @Test
    void aiRecipeDeletedDuringTheCall_doesNotPause() {
        generatedTarget();
        doThrow(new DataIntegrityViolationException("fk")).when(repo)
                .saveReady(any(), any(), any(), any(), any(), any());
        worker.run();
        worker.run();
        verify(repo, times(2)).next(any());
    }

    @Test
    void unreadableAiDocument_isMarkedFailed_andDoesNotBlockTheQueue() {
        GeneratedRecipe row = GeneratedRecipe.builder().id(ID).recipe(Map.of("x", 1)).build();
        when(repo.next(any())).thenReturn(Optional.of(GENERATED_TARGET));
        when(generated.findById(ID)).thenReturn(Optional.of(row));
        when(mapper.read(row)).thenThrow(new IllegalArgumentException("bad"));
        worker.run();
        verify(repo).saveFailed(eq(GENERATED_TARGET), eq(NutritionLines.hash(List.of())));
        verifyNoInteractions(estimator);
    }

    @Test
    void targetGoneBeforeReading_doesNothing() {
        when(recipes.findById(ID)).thenReturn(Optional.empty());
        worker.run();
        verifyNoInteractions(estimator);
    }
}
