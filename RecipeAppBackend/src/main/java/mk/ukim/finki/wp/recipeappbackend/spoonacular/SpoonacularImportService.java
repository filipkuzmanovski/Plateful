package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates one import run. Isolation rules:
 * - quota exhaustion (402) stops FETCHING but keeps everything saved;
 * - a failing recipe is logged + counted, the run continues;
 * - a failing cuisine request is logged + skipped, the run continues.
 * Dedup: existsBySpoonacularId pre-check, with the DB's UNIQUE constraint
 * on spoonacular_id as the backstop.
 */
@Service
public class SpoonacularImportService {

    private static final Logger log = LoggerFactory.getLogger(SpoonacularImportService.class);

    private final SpoonacularClient client;
    private final SpoonacularRecipeTranslator translator;
    private final SpoonacularRecipePersister persister;
    private final RecipeRepository recipeRepository;
    private final SpoonacularImportProperties properties;

    public SpoonacularImportService(SpoonacularClient client,
                                    SpoonacularRecipeTranslator translator,
                                    SpoonacularRecipePersister persister,
                                    RecipeRepository recipeRepository,
                                    SpoonacularImportProperties properties) {
        this.client = client;
        this.translator = translator;
        this.persister = persister;
        this.recipeRepository = recipeRepository;
        this.properties = properties;
    }

    public ImportSummary runImport() {
        List<ImportSummary.CuisineResult> results = new ArrayList<>();
        boolean quotaExhausted = false;

        for (String cuisine : properties.cuisines()) {
            SpoonacularSearchResponse response;
            try {
                response = client.searchByCuisine(cuisine, properties.recipesPerCuisine(), properties.offset());
            } catch (QuotaExceededException e) {
                log.warn("Spoonacular quota exhausted at cuisine '{}' — stopping; saved work is kept", cuisine);
                quotaExhausted = true;
                break;
            } catch (RestClientException e) {
                log.error("Request for cuisine '{}' failed — skipping this cuisine", cuisine, e);
                results.add(new ImportSummary.CuisineResult(cuisine, 0, 0, 0));
                continue;
            }

            results.add(importCuisine(cuisine, response));
        }
        return new ImportSummary(results, quotaExhausted);
    }

    private ImportSummary.CuisineResult importCuisine(String cuisine, SpoonacularSearchResponse response) {
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        List<SpoonacularSearchResponse.Result> recipes =
                response.results() != null ? response.results() : List.of();

        for (SpoonacularSearchResponse.Result result : recipes) {
            try {
                if (recipeRepository.existsBySpoonacularId(result.id())) {
                    skipped++;
                    continue;
                }
                SpoonacularRecipeTranslator.TranslationResult translated =
                        translator.translate(result, cuisine);
                persister.persist(translated.recipe(), translated.ingredients());
                imported++;
            } catch (Exception e) {
                failed++;
                log.error("Import of spoonacular recipe {} failed — continuing", result.id(), e);
            }
        }
        return new ImportSummary.CuisineResult(cuisine, imported, skipped, failed);
    }
}
