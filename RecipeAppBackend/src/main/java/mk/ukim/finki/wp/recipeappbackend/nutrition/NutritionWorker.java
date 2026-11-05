package mk.ukim.finki.wp.recipeappbackend.nutrition;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import mk.ukim.finki.wp.recipeappbackend.ai.AiProperties;
import mk.ukim.finki.wp.recipeappbackend.generation.GeneratedRecipeMapper;
import mk.ukim.finki.wp.recipeappbackend.generation.StoredRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.GeneratedRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeIngredient;
import mk.ukim.finki.wp.recipeappbackend.repository.GeneratedRecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.NutritionEstimateRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeIngredientRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Estimates one recipe per run (spec section 5). Not transactional: nothing is held open during the
 * AI call, and a recipe's answer is saved in a short transaction that takes the recipe's row lock and
 * checks the ingredients have not changed (spec section 6). A provider or unexpected error stores
 * nothing and pauses the job for five minutes, in memory.
 */
@Component
public class NutritionWorker {

    static final Duration PAUSE = Duration.ofMinutes(5);

    /** The columns are NUMERIC(8,1): a total at or above this cannot be stored. */
    static final double COLUMN_LIMIT = 10_000_000;

    private static final Logger log = LoggerFactory.getLogger(NutritionWorker.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final NutritionEstimator estimator;
    private final NutritionEstimateRepository estimates;
    private final RecipeRepository recipes;
    private final RecipeIngredientRepository ingredients;
    private final GeneratedRecipeRepository generated;
    private final GeneratedRecipeMapper mapper;
    private final AiProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    private volatile Instant pausedUntil;

    /** Used by Spring. */
    @Autowired
    public NutritionWorker(NutritionEstimator estimator, NutritionEstimateRepository estimates,
                           RecipeRepository recipes, RecipeIngredientRepository ingredients,
                           GeneratedRecipeRepository generated, GeneratedRecipeMapper mapper,
                           AiProperties properties, PlatformTransactionManager transactionManager) {
        this(estimator, estimates, recipes, ingredients, generated, mapper, properties,
                new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    /** Used by tests, which need to fix the clock. */
    NutritionWorker(NutritionEstimator estimator, NutritionEstimateRepository estimates,
                    RecipeRepository recipes, RecipeIngredientRepository ingredients,
                    GeneratedRecipeRepository generated, GeneratedRecipeMapper mapper,
                    AiProperties properties, TransactionTemplate transaction, Clock clock) {
        this.estimator = estimator;
        this.estimates = estimates;
        this.recipes = recipes;
        this.ingredients = ingredients;
        this.generated = generated;
        this.mapper = mapper;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public void run() {
        Instant now = clock.instant();
        if (pausedUntil != null && now.isBefore(pausedUntil)) {
            return;
        }
        try {
            OffsetDateTime startOfDay = now.atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay()
                    .atOffset(ZoneOffset.UTC);
            if (estimates.createdSince(startOfDay) >= properties.nutritionDailyLimit()) {
                return;
            }
            Optional<NutritionTarget> next = estimates.next(now.atOffset(ZoneOffset.UTC)
                    .minus(NutritionEstimates.RECENT));
            if (next.isEmpty()) {
                return;
            }
            NutritionTarget target = next.get();
            String outcome = target.kind() == NutritionTarget.Kind.RECIPE ? estimateRecipe(target)
                    : estimateGenerated(target);
            log.info("nutrition estimate kind={} id={} outcome={}", target.kind(), target.id(), outcome);
        } catch (RuntimeException e) {
            pausedUntil = clock.instant().plus(PAUSE);
            log.warn("nutrition estimate paused for {} after {}", PAUSE, e.getClass().getSimpleName());
        }
    }

    private String estimateRecipe(NutritionTarget target) {
        Optional<Recipe> found = recipes.findById(target.id());
        if (found.isEmpty()) {
            return "gone";
        }
        Recipe recipe = found.get();
        List<String> lines = NutritionLines.forRecipe(
                ingredients.findByRecipeIdOrderBySortOrderAsc(target.id()));
        String hash = NutritionLines.hash(lines);
        NutritionEstimator.Outcome outcome = estimator.estimate(recipe.getTitle(), recipe.getServings(), lines);
        String[] result = new String[1];
        transaction.executeWithoutResult(status -> {
            if (recipes.findForReviewById(target.id()).isEmpty()) {
                result[0] = "gone";
                return;
            }
            List<RecipeIngredient> current = ingredients.findByRecipeIdOrderBySortOrderAsc(target.id());
            if (!hash.equals(NutritionLines.hash(NutritionLines.forRecipe(current)))) {
                result[0] = "stale";
                return;
            }
            result[0] = save(target, outcome, hash);
        });
        return result[0];
    }

    private String estimateGenerated(NutritionTarget target) {
        Optional<GeneratedRecipe> found = generated.findById(target.id());
        if (found.isEmpty()) {
            return "gone";
        }
        StoredRecipe stored;
        try {
            stored = mapper.read(found.get());
        } catch (RuntimeException e) {
            return saveGenerated(target, null, NutritionLines.hash(List.of()));
        }
        List<String> lines = NutritionLines.forGenerated(stored);
        String hash = NutritionLines.hash(lines);
        NutritionEstimator.Outcome outcome = estimator.estimate(stored.title(), stored.servings(), lines);
        return saveGenerated(target, outcome, hash);
    }

    private String saveGenerated(NutritionTarget target, NutritionEstimator.Outcome outcome, String hash) {
        try {
            return save(target, outcome, hash);
        } catch (DataIntegrityViolationException e) {
            return "gone";
        }
    }

    /** A null outcome means the stored document could not be read, which is stored as failed. */
    private String save(NutritionTarget target, NutritionEstimator.Outcome outcome, String hash) {
        if (outcome instanceof NutritionEstimator.Ready ready && fits(ready.totals())) {
            estimates.saveReady(target, ready.totals(), MAPPER.writeValueAsString(ready.items()), hash,
                    ready.provider(), ready.model());
            return "ready";
        }
        estimates.saveFailed(target, hash);
        return "failed";
    }

    private static boolean fits(NutritionTotals t) {
        return t.calories() < COLUMN_LIMIT && t.protein() < COLUMN_LIMIT && t.fat() < COLUMN_LIMIT
                && t.carbs() < COLUMN_LIMIT;
    }
}
