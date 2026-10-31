package mk.ukim.finki.wp.recipeappbackend.generation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.ai.AiNotConfiguredException;
import mk.ukim.finki.wp.recipeappbackend.ai.AiQuotaExceededException;
import mk.ukim.finki.wp.recipeappbackend.ai.AiReply;
import mk.ukim.finki.wp.recipeappbackend.ai.AiUnavailableException;
import mk.ukim.finki.wp.recipeappbackend.ai.RecipeAi;
import mk.ukim.finki.wp.recipeappbackend.exception.NoPreferencesException;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.DailyMealsDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GeneratedRecipeSummaryDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.GeneratedRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.UserPreferences;
import mk.ukim.finki.wp.recipeappbackend.model.projection.DailySetRow;
import mk.ukim.finki.wp.recipeappbackend.model.projection.ReferenceRecipe;
import mk.ukim.finki.wp.recipeappbackend.repository.AiStateRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.GeneratedRecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserPreferencesRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Today's meals (spec section 6): once per user per local day the first request generates breakfast, lunch
 * and dinner in one AI call, checks each meal on its own, asks once more for only the meals that failed,
 * and stores the set; later requests are served from the store.
 *
 * <p>Only the request that wins the claim ({@link AiStateRepository#claimDailySet}) generates. When
 * generation fails, or is cooling down after a failure, the user's most recent earlier ready set is
 * returned with its own date ({@code fresh = false}), or "unavailable". An AI error is never an error
 * response here.
 *
 * <p>No database transaction or connection is held while an AI call runs. The three inserts and the
 * {@code ready} update share one short transaction; if the update finds the claim was taken over, that
 * transaction is rolled back and whatever the row now says is returned. Daily meals never touch the
 * 10-a-day counter. An {@link IllegalArgumentException} from the safety check (a stale food-rule key) is not
 * retryable: the set is marked failed and the exception goes on.
 */
@Service
public class DailyMealsService {

    private static final Logger log = LoggerFactory.getLogger(DailyMealsService.class);

    static final List<String> MEALS = RecipeSchema.MEALS;
    static final int EXAMPLES_PER_MEAL = 3;
    static final int MAX_CALLS = 2;
    static final int RECENT_DAYS = 7;

    private final UserPreferencesRepository preferences;
    private final UserRepository users;
    private final GeneratedRecipeRepository recipes;
    private final AiStateRepository state;
    private final ReferenceFinder references;
    private final PromptBuilder prompts;
    private final RecipeAi ai;
    private final DraftParser parser;
    private final SafetyCheck safety;
    private final NoveltyCheck novelty;
    private final GeneratedRecipeMapper mapper;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public DailyMealsService(UserPreferencesRepository preferences, UserRepository users,
                             GeneratedRecipeRepository recipes, AiStateRepository state,
                             ReferenceFinder references, PromptBuilder prompts, RecipeAi ai,
                             DraftParser parser, SafetyCheck safety, NoveltyCheck novelty,
                             GeneratedRecipeMapper mapper, PlatformTransactionManager transactionManager) {
        this(preferences, users, recipes, state, references, prompts, ai, parser, safety, novelty, mapper,
                new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    DailyMealsService(UserPreferencesRepository preferences, UserRepository users,
                      GeneratedRecipeRepository recipes, AiStateRepository state,
                      ReferenceFinder references, PromptBuilder prompts, RecipeAi ai,
                      DraftParser parser, SafetyCheck safety, NoveltyCheck novelty,
                      GeneratedRecipeMapper mapper, TransactionTemplate transaction, Clock clock) {
        this.preferences = preferences;
        this.users = users;
        this.recipes = recipes;
        this.state = state;
        this.references = references;
        this.prompts = prompts;
        this.ai = ai;
        this.parser = parser;
        this.safety = safety;
        this.novelty = novelty;
        this.mapper = mapper;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** One meal that passed every check, held in memory until all three have. */
    private record PassedMeal(RecipeDraft draft, Set<String> extras, double noveltyScore, AiReply reply,
                              List<ReferenceRecipe> examples) {
    }

    /** Exactly one field is set: the meal that passed, or the problem that rejected it. */
    private record Checked(PassedMeal passed, DraftProblem problem) {
        static Checked rejected(DraftProblem problem) {
            return new Checked(null, problem);
        }
    }

    /** Thrown inside the save transaction to roll it back when the claim was taken over. */
    private static final class ClaimLostException extends RuntimeException {
        ClaimLostException() {
            super("The daily set claim was taken over", null, false, false);
        }
    }

    public DailyMealsDto today(UUID userId, LocalDate date) {
        Optional<DailySetRow> existing = state.findDailySet(userId, date);
        if (existing.isPresent() && "ready".equals(existing.get().status())) {
            Optional<DailyMealsDto> served = ready(existing.get(), date);
            if (served.isPresent()) {
                return served.get();
            }
            return fallback(userId, date);
        }

        UserPreferences stored = preferences.findById(userId).orElseThrow(NoPreferencesException::new);
        UserFoodProfile profile = UserFoodProfile.of(stored);

        if (!state.claimDailySet(userId, date)) {
            return whatTheRowSays(userId, date);
        }

        try {
            Map<String, PassedMeal> passed = generate(userId, profile);
            if (passed == null) {
                markFailed(userId, date);
                return fallback(userId, date);
            }
            return save(userId, date, passed);
        } catch (AiQuotaExceededException | AiUnavailableException | AiNotConfiguredException e) {
            log.info("Daily meals not generated for {}: {}", date, e.getClass().getSimpleName());
            markFailed(userId, date);
            return fallback(userId, date);
        } catch (RuntimeException e) {
            markFailed(userId, date);
            throw e;
        }
    }

    /**
     * Steps 3 to 6: inspiration, one call for all three meals, per-meal checks, and one more call for only
     * the meals that failed. Returns the passed meals, or null when a meal still fails.
     */
    private Map<String, PassedMeal> generate(UUID userId, UserFoodProfile profile) {
        Map<String, List<ReferenceRecipe>> examples = new LinkedHashMap<>();
        for (String meal : MEALS) {
            examples.put(meal, references.dailyInspiration(meal, profile, EXAMPLES_PER_MEAL));
        }
        List<String> recentTitles = recipes.recentDailyTitles(userId, OffsetDateTime.now(clock).minusDays(RECENT_DAYS));

        Map<String, PassedMeal> passed = new LinkedHashMap<>();
        Map<String, DraftProblem> problems = new LinkedHashMap<>();
        Set<String> pending = new LinkedHashSet<>(MEALS);

        for (int call = 1; call <= MAX_CALLS && !pending.isEmpty(); call++) {
            AiReply reply = ai.complete(prompts.daily(profile, examples, recentTitles, pending, problems));
            Map<String, DraftParser.Result> parsed = parser.parseMeals(reply.json(), pending);

            problems = new LinkedHashMap<>();
            for (String meal : MEALS) {
                if (!pending.contains(meal)) {
                    continue;
                }
                Checked outcome = check(parsed.get(meal), profile, examples.get(meal), reply);
                if (outcome.passed() != null) {
                    passed.put(meal, outcome.passed());
                } else {
                    problems.put(meal, outcome.problem());
                }
            }
            pending = new LinkedHashSet<>(problems.keySet());
        }
        return pending.isEmpty() ? passed : null;
    }

    private Checked check(DraftParser.Result parsed, UserFoodProfile profile, List<ReferenceRecipe> examples,
                         AiReply reply) {
        if (parsed == null) {
            return Checked.rejected(new DraftProblem(DraftParser.UNREADABLE));
        }
        if (parsed.problem() != null) {
            return Checked.rejected(parsed.problem());
        }
        RecipeDraft draft = parsed.draft();
        SafetyCheck.SafetyResult checked = safety.check(draft, profile, Set.of(), SafetyCheck.Mode.ANY);
        if (checked.problem().isPresent()) {
            return Checked.rejected(checked.problem().get());
        }
        NoveltyCheck.NoveltyResult novel = novelty.check(draft, references.comparisonSet(draft, examples));
        if (novel.problem().isPresent()) {
            return Checked.rejected(novel.problem().get());
        }
        return new Checked(new PassedMeal(draft, checked.extras(), novel.score(), reply, examples), null);
    }

    /** Step 7: the three inserts and the {@code ready} update in one short transaction. */
    private DailyMealsDto save(UUID userId, LocalDate date, Map<String, PassedMeal> passed) {
        Map<String, GeneratedRecipe> rows;
        try {
            rows = transaction.execute(status -> {
                Map<String, GeneratedRecipe> saved = new LinkedHashMap<>();
                for (String meal : MEALS) {
                    saved.put(meal, recipes.save(row(userId, date, meal, passed.get(meal))));
                }
                // The set references the rows, so they must reach the database before the update.
                recipes.flush();
                boolean finished = state.finishDailySet(userId, date,
                        saved.get("breakfast").getId(), saved.get("lunch").getId(), saved.get("dinner").getId());
                if (!finished) {
                    throw new ClaimLostException();
                }
                return saved;
            });
        } catch (ClaimLostException e) {
            log.info("Daily meals for {} were finished by someone else; discarding this result", date);
            return whatTheRowSays(userId, date);
        }

        Map<String, GeneratedRecipeSummaryDto> meals = new LinkedHashMap<>();
        rows.forEach((meal, row) -> meals.put(meal, mapper.toSummary(row)));
        return DailyMealsDto.ready(date, true, meals);
    }

    private GeneratedRecipe row(UUID userId, LocalDate date, String meal, PassedMeal p) {
        StoredRecipe recipe = mapper.fromDraft(p.draft(), p.extras());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("date", date.toString());
        request.put("mealType", meal);
        Long[] referenceIds = p.examples().stream().map(ReferenceRecipe::id).toArray(Long[]::new);
        return GeneratedRecipe.builder()
                .user(users.getReferenceById(userId))
                .kind("daily")
                .mealType(meal)
                .title(recipe.title())
                .recipe(mapper.toDocument(recipe))
                .request(request)
                .provider(p.reply().provider())
                .model(p.reply().model())
                .noveltyScore(BigDecimal.valueOf(p.noveltyScore()).setScale(3, RoundingMode.HALF_UP))
                .referenceIds(referenceIds)
                .build();
    }

    /** The row as it stands now, for a caller that does not (or no longer) own the generation. */
    private DailyMealsDto whatTheRowSays(UUID userId, LocalDate date) {
        Optional<DailySetRow> current = state.findDailySet(userId, date);
        if (current.isPresent()) {
            DailySetRow row = current.get();
            if ("ready".equals(row.status())) {
                Optional<DailyMealsDto> served = ready(row, date);
                return served.isPresent() ? served.get() : fallback(userId, date);
            }
            if ("generating".equals(row.status())) {
                return DailyMealsDto.generating();
            }
        }
        return fallback(userId, date);
    }

    /** The user's most recent earlier ready set, labelled with its own date, or "unavailable". */
    private DailyMealsDto fallback(UUID userId, LocalDate date) {
        Optional<DailySetRow> earlier = state.latestReadyBefore(userId, date);
        if (earlier.isPresent()) {
            Optional<DailyMealsDto> served = ready(earlier.get(), earlier.get().date(), false);
            if (served.isPresent()) {
                return served.get();
            }
        }
        return DailyMealsDto.unavailable();
    }

    private Optional<DailyMealsDto> ready(DailySetRow row, LocalDate date) {
        return ready(row, date, true);
    }

    private Optional<DailyMealsDto> ready(DailySetRow row, LocalDate date, boolean fresh) {
        if (row.breakfastId() == null || row.lunchId() == null || row.dinnerId() == null) {
            return Optional.empty();
        }
        Map<UUID, GeneratedRecipe> byId = new LinkedHashMap<>();
        for (GeneratedRecipe recipe : recipes.findAllById(List.of(row.breakfastId(), row.lunchId(), row.dinnerId()))) {
            byId.put(recipe.getId(), recipe);
        }
        List<UUID> ids = List.of(row.breakfastId(), row.lunchId(), row.dinnerId());
        if (!byId.keySet().containsAll(ids)) {
            log.warn("A ready daily set for {} refers to a recipe that no longer exists", row.date());
            return Optional.empty();
        }
        Map<String, GeneratedRecipeSummaryDto> meals = new LinkedHashMap<>();
        for (int i = 0; i < MEALS.size(); i++) {
            meals.put(MEALS.get(i), mapper.toSummary(byId.get(ids.get(i))));
        }
        return Optional.of(DailyMealsDto.ready(date, fresh, meals));
    }

    /** Marking the set failed must not hide the error that ended the generation, nor turn a fallback into one. */
    private void markFailed(UUID userId, LocalDate date) {
        try {
            if (!state.failDailySet(userId, date)) {
                log.info("Daily set for {} was no longer ours to mark failed", date);
            }
        } catch (RuntimeException e) {
            log.error("Could not mark the daily set failed", e);
        }
    }
}
