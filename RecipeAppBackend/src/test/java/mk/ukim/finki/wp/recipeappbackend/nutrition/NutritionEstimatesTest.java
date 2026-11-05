package mk.ukim.finki.wp.recipeappbackend.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.NutritionEstimateDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.GeneratedRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeIngredient;
import mk.ukim.finki.wp.recipeappbackend.repository.NutritionEstimateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class NutritionEstimatesTest {

    private static final UUID ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    private final NutritionEstimateRepository repo = mock(NutritionEstimateRepository.class);
    private final NutritionEstimates estimates =
            new NutritionEstimates(repo, Clock.fixed(NOW, ZoneOffset.UTC));

    private static StoredEstimate stored(String status, double calories, double protein, double fat, double carbs) {
        return new StoredEstimate(status, BigDecimal.valueOf(calories), BigDecimal.valueOf(protein),
                BigDecimal.valueOf(fat), BigDecimal.valueOf(carbs), "hash");
    }

    private static StoredEstimate failed() {
        return new StoredEstimate("failed", null, null, null, null, "hash");
    }

    private static Recipe upload(RecipeStatus status, Integer servings) {
        return Recipe.builder().id(ID).source("user").status(status).servings(servings).build();
    }

    private static GeneratedRecipe ai(OffsetDateTime createdAt, OffsetDateTime savedAt) {
        return GeneratedRecipe.builder().id(ID).createdAt(createdAt).savedAt(savedAt).build();
    }

    private static RecipeIngredient ingredient(String name, String text) {
        return RecipeIngredient.builder().ingredientName(name).originalText(text).build();
    }

    private static OffsetDateTime at(int hoursFromNow) {
        return OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusHours(hoursFromNow);
    }

    @Test
    void uploaded_approved_noRow_isPending() {
        assertThat(estimates.forRecipe(upload(RecipeStatus.APPROVED, 4)).status()).isEqualTo("pending");
    }

    @Test
    void uploaded_ready_roundsPerServing() {
        when(repo.forRecipe(ID)).thenReturn(Optional.of(stored("ready", 1800, 112, 72, 168)));

        assertThat(estimates.forRecipe(upload(RecipeStatus.APPROVED, 4)))
                .isEqualTo(new NutritionEstimateDto("ready", 450, 28, 18, 42));
    }

    @Test
    void caloriesRoundToNearest10_macrosToNearestGram() {
        when(repo.forRecipe(ID)).thenReturn(Optional.of(stored("ready", 1816, 110, 70, 166)));

        // 454, 27.5, 17.5, 41.5
        assertThat(estimates.forRecipe(upload(RecipeStatus.APPROVED, 4)))
                .isEqualTo(new NutritionEstimateDto("ready", 450, 28, 18, 42));
    }

    @Test
    void belowTenKcal_isWholeKcal_andSmallMacrosRoundToZero() {
        when(repo.forRecipe(ID)).thenReturn(Optional.of(stored("ready", 96, 2, 3.6, 18)));

        // 8, 0.17, 0.3, 1.5
        assertThat(estimates.forRecipe(upload(RecipeStatus.APPROVED, 12)))
                .isEqualTo(new NutritionEstimateDto("ready", 8, 0, 0, 2));
    }

    @Test
    void servingsChange_recalculatesWithNoAiOrWrite() {
        when(repo.forRecipe(ID)).thenReturn(Optional.of(stored("ready", 1800, 112, 72, 168)));

        assertThat(estimates.forRecipe(upload(RecipeStatus.APPROVED, 2)).calories()).isEqualTo(900);
        verify(repo, never()).deleteForRecipeIfHashDiffers(any(), any());
    }

    @Test
    void uploaded_failedRow_isNone() {
        when(repo.forRecipe(ID)).thenReturn(Optional.of(failed()));

        assertThat(estimates.forRecipe(upload(RecipeStatus.APPROVED, 4))).isEqualTo(NutritionEstimateDto.none());
    }

    @ParameterizedTest
    @EnumSource(value = RecipeStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "APPROVED")
    void uploaded_notApproved_isNone_evenWithAReadyRow(RecipeStatus status) {
        when(repo.forRecipe(ID)).thenReturn(Optional.of(stored("ready", 1800, 112, 72, 168)));

        assertThat(estimates.forRecipe(upload(status, 4))).isEqualTo(NutritionEstimateDto.none());
    }

    @ParameterizedTest
    @CsvSource({"1, ready", "0.9, none", "3000, ready", "3000.1, none"})
    void perServingCaloriesOutsideOneTo3000_areNone(double kcalPerServing, String status) {
        when(repo.forRecipe(ID)).thenReturn(Optional.of(stored("ready", kcalPerServing * 4, 10, 10, 10)));

        assertThat(estimates.forRecipe(upload(RecipeStatus.APPROVED, 4)).status()).isEqualTo(status);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = 0)
    void uploaded_noServings_isNone_evenWithAReadyRow(Integer servings) {
        when(repo.forRecipe(ID)).thenReturn(Optional.of(stored("ready", 1800, 112, 72, 168)));

        assertThat(estimates.forRecipe(upload(RecipeStatus.APPROVED, servings)))
                .isEqualTo(NutritionEstimateDto.none());
    }

    @Test
    void imported_isNone_andNeverQueried() {
        Recipe imported = Recipe.builder().id(ID).source("spoonacular").status(RecipeStatus.APPROVED)
                .servings(4).build();

        assertThat(estimates.forRecipe(imported)).isEqualTo(NutritionEstimateDto.none());
        verify(repo, never()).forRecipe(any());
    }

    @Test
    void ai_ready_rounds() {
        when(repo.forGenerated(ID)).thenReturn(Optional.of(stored("ready", 1800, 112, 72, 168)));

        assertThat(estimates.forGenerated(ai(at(0), null), 4))
                .isEqualTo(new NutritionEstimateDto("ready", 450, 28, 18, 42));
    }

    @Test
    void ai_failed_isNone() {
        when(repo.forGenerated(ID)).thenReturn(Optional.of(failed()));

        assertThat(estimates.forGenerated(ai(at(0), null), 4)).isEqualTo(NutritionEstimateDto.none());
    }

    @Test
    void ai_noRow_underADay_isPending() {
        assertThat(estimates.forGenerated(ai(at(-23), null), 4).status()).isEqualTo("pending");
    }

    @Test
    void ai_noRow_saved_isPending() {
        assertThat(estimates.forGenerated(ai(at(-40 * 24), at(-24)), 4).status()).isEqualTo("pending");
    }

    @Test
    void ai_noRow_oldUnsaved_isNone() {
        assertThat(estimates.forGenerated(ai(at(-25), null), 4)).isEqualTo(NutritionEstimateDto.none());
    }

    @Test
    void removeIfIngredientsChanged_passesTheCurrentHash() {
        estimates.removeIfIngredientsChanged(ID, List.of(ingredient("flour", "2 cups")));

        verify(repo).deleteForRecipeIfHashDiffers(ID, NutritionLines.hash(List.of("flour | 2 cups")));
    }
}
