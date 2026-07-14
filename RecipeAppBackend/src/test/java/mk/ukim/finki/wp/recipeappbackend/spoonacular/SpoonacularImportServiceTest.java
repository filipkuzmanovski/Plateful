package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpoonacularImportServiceTest {

    @Mock
    SpoonacularClient client;
    @Mock
    SpoonacularRecipePersister persister;
    @Mock
    RecipeRepository recipeRepository;

    private SpoonacularImportService serviceWith(List<String> cuisines) {
        SpoonacularImportProperties props =
                new SpoonacularImportProperties(true, cuisines, 40, 0);
        return new SpoonacularImportService(client, new SpoonacularRecipeTranslator(),
                persister, recipeRepository, props);
    }

    private static SpoonacularSearchResponse responseWithIds(long... ids) {
        List<SpoonacularSearchResponse.Result> results = new java.util.ArrayList<>();
        for (long id : ids) {
            results.add(new SpoonacularSearchResponse.Result(
                    id, "Recipe " + id, null, 2, 20, null, null, null, null,
                    "Cook it.", null, false, false, false, false,
                    List.of(), null,
                    List.of(new SpoonacularSearchResponse.ExtendedIngredient(
                            "salt", null, "a pinch of salt", null))));
        }
        return new SpoonacularSearchResponse(results);
    }

    @Test
    void importsNewRecipesAndSkipsExisting() {
        when(client.searchByCuisine("italian", 40, 0)).thenReturn(responseWithIds(1L, 2L));
        when(recipeRepository.existsBySpoonacularId(1L)).thenReturn(true);   // already imported
        when(recipeRepository.existsBySpoonacularId(2L)).thenReturn(false);

        ImportSummary summary = serviceWith(List.of("italian")).runImport();

        assertThat(summary.totalImported()).isEqualTo(1);
        assertThat(summary.totalSkipped()).isEqualTo(1);
        assertThat(summary.totalFailed()).isZero();
        assertThat(summary.quotaExhausted()).isFalse();
        verify(persister).persist(any(Recipe.class), anyList());
    }

    @Test
    void quotaExhaustionStopsFurtherCuisinesButKeepsEarlierResults() {
        when(client.searchByCuisine("italian", 40, 0)).thenReturn(responseWithIds(1L));
        when(recipeRepository.existsBySpoonacularId(1L)).thenReturn(false);
        when(client.searchByCuisine("thai", 40, 0)).thenThrow(new QuotaExceededException());

        ImportSummary summary = serviceWith(List.of("italian", "thai", "greek")).runImport();

        assertThat(summary.totalImported()).isEqualTo(1);
        assertThat(summary.quotaExhausted()).isTrue();
        verify(client, never()).searchByCuisine(eq("greek"), anyInt(), anyInt());
    }

    @Test
    void oneFailingRecipeDoesNotAbortTheRun() {
        when(client.searchByCuisine("italian", 40, 0)).thenReturn(responseWithIds(1L, 2L));
        when(recipeRepository.existsBySpoonacularId(1L)).thenReturn(false);
        when(recipeRepository.existsBySpoonacularId(2L)).thenReturn(false);
        doThrow(new RuntimeException("boom")).doNothing()
                .when(persister).persist(any(Recipe.class), anyList());

        ImportSummary summary = serviceWith(List.of("italian")).runImport();

        assertThat(summary.totalImported()).isEqualTo(1);
        assertThat(summary.totalFailed()).isEqualTo(1);
    }

    @Test
    void networkErrorOnOneCuisineSkipsItAndContinues() {
        when(client.searchByCuisine(eq("italian"), anyInt(), anyInt()))
                .thenThrow(new RestClientException("connection reset"));
        when(client.searchByCuisine("thai", 40, 0)).thenReturn(responseWithIds(3L));
        when(recipeRepository.existsBySpoonacularId(3L)).thenReturn(false);

        ImportSummary summary = serviceWith(List.of("italian", "thai")).runImport();

        assertThat(summary.totalImported()).isEqualTo(1);
        assertThat(summary.results()).hasSize(2);
        assertThat(summary.results().get(0).failed()).isZero(); // cuisine skipped, not counted as recipe failures
    }

    @Test
    void nullResultsListIsTolerated() {
        lenient().when(client.searchByCuisine("italian", 40, 0))
                .thenReturn(new SpoonacularSearchResponse(null));

        ImportSummary summary = serviceWith(List.of("italian")).runImport();

        assertThat(summary.totalImported()).isZero();
        assertThat(summary.totalFailed()).isZero();
    }
}
