package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpoonacularImportRunnerTest {

    @Mock
    SpoonacularImportService importService;

    @Test
    void missingApiKeyFailsFastWithoutCallingTheService() {
        SpoonacularImportRunner runner = new SpoonacularImportRunner(
                new SpoonacularProperties("", "https://api.spoonacular.com"), importService);

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPOONACULAR_API_KEY");
        verify(importService, never()).runImport();
    }

    @Test
    void runsImportAndSurvivesWhenKeyPresent() throws Exception {
        when(importService.runImport()).thenReturn(new ImportSummary(
                List.of(new ImportSummary.CuisineResult("italian", 3, 1, 0)), false));
        SpoonacularImportRunner runner = new SpoonacularImportRunner(
                new SpoonacularProperties("real-key", "https://api.spoonacular.com"), importService);

        runner.run(null);

        verify(importService).runImport();
    }
}
