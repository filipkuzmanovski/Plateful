package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Entry point for the import. Exists ONLY when
 * spoonacular.import.enabled=true (pass --spoonacular.import.enabled=true
 * in the run configuration); normal app starts are completely unaffected.
 * Runs once at startup; the app keeps serving afterward.
 */
@Component
@ConditionalOnProperty(prefix = "spoonacular.import", name = "enabled", havingValue = "true")
public class SpoonacularImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SpoonacularImportRunner.class);

    private final SpoonacularProperties properties;
    private final SpoonacularImportService importService;

    public SpoonacularImportRunner(SpoonacularProperties properties,
                                   SpoonacularImportService importService) {
        this.properties = properties;
        this.importService = importService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "Spoonacular import is enabled but no API key is set. "
                            + "Set the SPOONACULAR_API_KEY environment variable "
                            + "(IntelliJ: Run > Edit Configurations > Environment variables).");
        }

        log.info("Starting Spoonacular import...");
        ImportSummary summary = importService.runImport();

        for (ImportSummary.CuisineResult r : summary.results()) {
            log.info("  {}: imported={}, skipped={}, failed={}",
                    r.cuisine(), r.imported(), r.skipped(), r.failed());
        }
        log.info("Spoonacular import finished: imported={}, skipped={}, failed={}",
                summary.totalImported(), summary.totalSkipped(), summary.totalFailed());
        if (summary.quotaExhausted()) {
            log.warn("Daily quota was exhausted mid-run. Everything fetched so far is saved. "
                    + "Re-run the same command after the quota resets — already-imported "
                    + "recipes are skipped automatically.");
        }
    }
}
