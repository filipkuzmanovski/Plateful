package mk.ukim.finki.wp.recipeappbackend.generation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.ai.AiProperties;
import mk.ukim.finki.wp.recipeappbackend.ai.AiReply;
import mk.ukim.finki.wp.recipeappbackend.ai.AiRequest;
import mk.ukim.finki.wp.recipeappbackend.ai.RecipeAi;
import mk.ukim.finki.wp.recipeappbackend.exception.DailyLimitException;
import mk.ukim.finki.wp.recipeappbackend.exception.GenerationFailedException;
import mk.ukim.finki.wp.recipeappbackend.exception.GenerationRequestException;
import mk.ukim.finki.wp.recipeappbackend.exception.NoPreferencesException;
import mk.ukim.finki.wp.recipeappbackend.food.IngredientNames;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.GenerateRequestDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GenerateResultDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.GeneratedRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.UserPreferences;
import mk.ukim.finki.wp.recipeappbackend.model.projection.Pairing;
import mk.ukim.finki.wp.recipeappbackend.model.projection.ReferenceRecipe;
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
 * "Cook with what I have" (spec section 5): preferences, request check, usage reservation, inspiration,
 * up to three prompt / AI call / parse / safety / novelty attempts inside a time budget, and a saved row.
 *
 * <p>No database transaction or connection is held while an AI call runs: preferences are read and the
 * generation is reserved as their own statements, and only the final insert runs in a short transaction.
 *
 * <p>A failed generation is free: whatever ends it without a recipe (failed checks, an AI error, a bug)
 * gives the reservation back before the exception goes on. An {@link IllegalArgumentException} from the
 * safety check (a stale food-rule key) is not a draft problem and is never retried.
 */
@Service
public class RecipeGenerationService {

    private static final Logger log = LoggerFactory.getLogger(RecipeGenerationService.class);

    static final int MIN_INGREDIENTS = 2;
    static final int MAX_INGREDIENTS = 15;
    static final int MAX_ATTEMPTS = 3;
    static final int EXAMPLES = 6;
    static final Duration BUDGET = Duration.ofSeconds(75);
    static final Duration MIN_TIME_FOR_ATTEMPT = Duration.ofSeconds(30);
    static final Set<String> MEAL_TYPES = Set.of("breakfast", "lunch", "dinner");
    static final String CONFLICT_DETAIL = "Some ingredients conflict with your food preferences.";

    private final UserPreferencesRepository preferences;
    private final UserRepository users;
    private final GeneratedRecipeRepository recipes;
    private final AiUsageService usage;
    private final ReferenceFinder references;
    private final PromptBuilder prompts;
    private final RecipeAi ai;
    private final DraftParser parser;
    private final SafetyCheck safety;
    private final NoveltyCheck novelty;
    private final GeneratedRecipeMapper mapper;
    private final TransactionTemplate transaction;
    private final int dailyLimit;
    private final Clock clock;

    @Autowired
    public RecipeGenerationService(UserPreferencesRepository preferences, UserRepository users,
                                   GeneratedRecipeRepository recipes, AiUsageService usage,
                                   ReferenceFinder references, PromptBuilder prompts, RecipeAi ai,
                                   DraftParser parser, SafetyCheck safety, NoveltyCheck novelty,
                                   GeneratedRecipeMapper mapper, PlatformTransactionManager transactionManager,
                                   AiProperties properties) {
        this(preferences, users, recipes, usage, references, prompts, ai, parser, safety, novelty, mapper,
                new TransactionTemplate(transactionManager), properties.dailyLimit(), Clock.systemUTC());
    }

    RecipeGenerationService(UserPreferencesRepository preferences, UserRepository users,
                            GeneratedRecipeRepository recipes, AiUsageService usage,
                            ReferenceFinder references, PromptBuilder prompts, RecipeAi ai,
                            DraftParser parser, SafetyCheck safety, NoveltyCheck novelty,
                            GeneratedRecipeMapper mapper, TransactionTemplate transaction, int dailyLimit,
                            Clock clock) {
        this.preferences = preferences;
        this.users = users;
        this.recipes = recipes;
        this.usage = usage;
        this.references = references;
        this.prompts = prompts;
        this.ai = ai;
        this.parser = parser;
        this.safety = safety;
        this.novelty = novelty;
        this.mapper = mapper;
        this.transaction = transaction;
        this.dailyLimit = dailyLimit;
        this.clock = clock;
    }

    public GenerateResultDto generate(UUID userId, GenerateRequestDto body) {
        UserPreferences stored = preferences.findById(userId).orElseThrow(NoPreferencesException::new);
        UserFoodProfile profile = UserFoodProfile.of(stored);

        Set<String> names = checkRequest(body, profile);
        String mealType = body.mealType() == null || body.mealType().isBlank() ? null : body.mealType();

        OptionalInt used = usage.reserve(userId);
        if (used.isEmpty()) {
            throw new DailyLimitException();
        }
        try {
            GeneratedRecipe row = createRecipe(userId, body, names, mealType, profile);
            return new GenerateResultDto(mapper.toDto(row), Math.max(0, dailyLimit - used.getAsInt()));
        } catch (RuntimeException e) {
            refundQuietly(userId);
            throw e;
        }
    }

    /**
     * Step 2 of spec section 5.1. Returns the normalised, de-duplicated ingredient names. Conflicts are
     * checked on the raw strings, because the frontend marks chips by the exact text it sent.
     */
    private Set<String> checkRequest(GenerateRequestDto body, UserFoodProfile profile) {
        List<String> raw = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (String ingredient : body.ingredients() == null ? List.<String>of() : body.ingredients()) {
            if (ingredient == null) {
                continue;
            }
            raw.add(ingredient);
            IngredientNames.normalise(ingredient).ifPresent(names::add);
        }
        if (names.size() < MIN_INGREDIENTS || names.size() > MAX_INGREDIENTS) {
            throw new GenerationRequestException(
                    "Choose between " + MIN_INGREDIENTS + " and " + MAX_INGREDIENTS + " different ingredients.");
        }
        String mealType = body.mealType();
        if (mealType != null && !mealType.isBlank() && !MEAL_TYPES.contains(mealType)) {
            throw new GenerationRequestException("The meal type must be breakfast, lunch or dinner.");
        }
        List<SafetyCheck.Conflict> conflicts = safety.conflicts(raw, profile);
        if (!conflicts.isEmpty()) {
            throw new GenerationRequestException(CONFLICT_DETAIL, conflicts);
        }
        return names;
    }

    private GeneratedRecipe createRecipe(UUID userId, GenerateRequestDto body, Set<String> names, String mealType,
                                         UserFoodProfile profile) {
        Instant start = clock.instant();

        List<ReferenceRecipe> examples = references.inspiration(names, mealType, profile, EXAMPLES);
        Map<String, List<Pairing>> pairings = references.pairings(names, profile);
        PromptBuilder.CookRequest request =
                new PromptBuilder.CookRequest(List.copyOf(names), mealType, body.onlyWhatIHave());
        SafetyCheck.Mode mode =
                body.onlyWhatIHave() ? SafetyCheck.Mode.ONLY_MINE : SafetyCheck.Mode.STAPLES_AND_EXTRAS;

        List<DraftProblem> problems = new ArrayList<>();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (attempt > 1 && timeLeft(start).compareTo(MIN_TIME_FOR_ATTEMPT) < 0) {
                log.info("AI generation stopped after {} attempt(s): under {} s of the budget left",
                        attempt - 1, MIN_TIME_FOR_ATTEMPT.toSeconds());
                break;
            }
            AiRequest aiRequest = prompts.cook(request, profile, examples, pairings, List.copyOf(problems));
            AiReply reply = ai.complete(aiRequest);

            DraftParser.Result parsed = parser.parse(reply.json());
            if (parsed.problem() != null) {
                problems.add(parsed.problem());
                continue;
            }
            RecipeDraft draft = parsed.draft();

            SafetyCheck.SafetyResult checked = safety.check(draft, profile, names, mode);
            if (checked.problem().isPresent()) {
                problems.add(checked.problem().get());
                continue;
            }

            NoveltyCheck.NoveltyResult novel = novelty.check(draft, references.comparisonSet(draft, examples));
            if (novel.problem().isPresent()) {
                problems.add(novel.problem().get());
                continue;
            }

            StoredRecipe recipe = mapper.fromDraft(draft, checked.extras());
            return save(userId, body, names, mealType, examples, reply, recipe, novel.score());
        }
        throw new GenerationFailedException();
    }

    private Duration timeLeft(Instant start) {
        return BUDGET.minus(Duration.between(start, clock.instant()));
    }

    /** The one short transaction: a single insert. */
    private GeneratedRecipe save(UUID userId, GenerateRequestDto body, Set<String> names, String mealType,
                                 List<ReferenceRecipe> examples, AiReply reply, StoredRecipe recipe,
                                 double noveltyScore) {
        Map<String, Object> requestDocument = new LinkedHashMap<>();
        requestDocument.put("ingredients", List.copyOf(names));
        requestDocument.put("mealType", mealType);
        requestDocument.put("onlyWhatIHave", body.onlyWhatIHave());

        Long[] referenceIds = examples.stream().map(ReferenceRecipe::id).toArray(Long[]::new);

        return transaction.execute(status -> {
            GeneratedRecipe row = GeneratedRecipe.builder()
                    .user(users.getReferenceById(userId))
                    .kind("cook")
                    .mealType(mealType)
                    .title(recipe.title())
                    .recipe(mapper.toDocument(recipe))
                    .request(requestDocument)
                    .provider(reply.provider())
                    .model(reply.model())
                    .noveltyScore(BigDecimal.valueOf(noveltyScore).setScale(3, RoundingMode.HALF_UP))
                    .referenceIds(referenceIds)
                    .build();
            return recipes.save(row);
        });
    }

    /** A failed refund must not hide the error that ended the generation. */
    private void refundQuietly(UUID userId) {
        try {
            usage.refund(userId);
        } catch (RuntimeException e) {
            log.error("Could not refund an AI generation", e);
        }
    }
}
