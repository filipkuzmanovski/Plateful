package mk.ukim.finki.wp.recipeappbackend.generation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.ai.AiImage;
import mk.ukim.finki.wp.recipeappbackend.ai.AiProperties;
import mk.ukim.finki.wp.recipeappbackend.ai.AiReply;
import mk.ukim.finki.wp.recipeappbackend.ai.RecipeAi;
import mk.ukim.finki.wp.recipeappbackend.exception.DailyLimitException;
import mk.ukim.finki.wp.recipeappbackend.exception.GenerationFailedException;
import mk.ukim.finki.wp.recipeappbackend.exception.NoDishException;
import mk.ukim.finki.wp.recipeappbackend.exception.NoPreferencesException;
import mk.ukim.finki.wp.recipeappbackend.exception.PostImportRequestException;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GenerateResultDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.GeneratedRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.UserPreferences;
import mk.ukim.finki.wp.recipeappbackend.repository.GeneratedRecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserPreferencesRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "From a post" (spec section 4.2): preferences, request check, photo preparation, a reserved daily
 * generation, the screenshot reading (one retry if it is unreadable and enough time is left), up to three
 * prompt / AI call / parse / safety / swap / own-words attempts inside a time budget, and a saved row.
 *
 * <p>Screenshots are read and the recipe written by the Groq-only chains ({@code visionAi}, {@code postAi}); a
 * caption on its own goes to the normal text chain. No database transaction or connection is held while an AI
 * call runs: preferences are read and the generation is reserved as their own statements, and only the final
 * insert runs in a short transaction.
 *
 * <p>A failed import is free: whatever ends it without a recipe after the reservation (no dish, failed checks,
 * an AI error, a bug) gives the generation back before the exception goes on. The caption, the screenshots and
 * the reading live only in memory: the saved row keeps the recipe and two counts, and the one INFO line per
 * import holds counts, booleans and, on failure, the attempt reasons (plus a line when the time budget stops
 * the attempts).
 */
@Service
public class PostImportService {

    private static final Logger log = LoggerFactory.getLogger(PostImportService.class);

    static final Duration BUDGET = Duration.ofSeconds(90);
    static final Duration MIN_TIME_FOR_READING_RETRY = Duration.ofSeconds(60);
    static final Duration MIN_TIME_FOR_ATTEMPT = Duration.ofSeconds(30);
    static final int MAX_READING_ATTEMPTS = 2;
    static final int MAX_ATTEMPTS = 3;
    static final int MAX_CAPTION = 2200;
    static final int MAX_SCREENSHOTS = 2;
    static final String INVALID_POST_DETAIL = "Add a caption (up to 2,200 characters) or 1 to 2 screenshots.";

    private final UserPreferencesRepository preferences;
    private final UserRepository users;
    private final GeneratedRecipeRepository recipes;
    private final AiUsageService usage;
    private final PhotoPreparer preparer;
    private final PromptBuilder prompts;
    private final RecipeAi visionAi;
    private final RecipeAi postAi;
    private final RecipeAi recipeAi;
    private final PostReadingParser readingParser;
    private final PostReplyParser replyParser;
    private final SafetyCheck safety;
    private final GeneratedRecipeMapper mapper;
    private final TransactionTemplate transaction;
    private final int dailyLimit;
    private final Clock clock;

    @Autowired
    public PostImportService(UserPreferencesRepository preferences, UserRepository users,
                             GeneratedRecipeRepository recipes, AiUsageService usage, PhotoPreparer preparer,
                             PromptBuilder prompts, @Qualifier("visionAi") RecipeAi visionAi,
                             @Qualifier("postAi") RecipeAi postAi, RecipeAi recipeAi,
                             PostReadingParser readingParser, PostReplyParser replyParser, SafetyCheck safety,
                             GeneratedRecipeMapper mapper, PlatformTransactionManager transactionManager,
                             AiProperties properties) {
        this(preferences, users, recipes, usage, preparer, prompts, visionAi, postAi, recipeAi, readingParser,
                replyParser, safety, mapper, new TransactionTemplate(transactionManager), properties.dailyLimit(),
                Clock.systemUTC());
    }

    PostImportService(UserPreferencesRepository preferences, UserRepository users, GeneratedRecipeRepository recipes,
                      AiUsageService usage, PhotoPreparer preparer, PromptBuilder prompts, RecipeAi visionAi,
                      RecipeAi postAi, RecipeAi recipeAi, PostReadingParser readingParser,
                      PostReplyParser replyParser, SafetyCheck safety, GeneratedRecipeMapper mapper,
                      TransactionTemplate transaction, int dailyLimit, Clock clock) {
        this.preferences = preferences;
        this.users = users;
        this.recipes = recipes;
        this.usage = usage;
        this.preparer = preparer;
        this.prompts = prompts;
        this.visionAi = visionAi;
        this.postAi = postAi;
        this.recipeAi = recipeAi;
        this.readingParser = readingParser;
        this.replyParser = replyParser;
        this.safety = safety;
        this.mapper = mapper;
        this.transaction = transaction;
        this.dailyLimit = dailyLimit;
        this.clock = clock;
    }

    /**
     * Turns a post into a private recipe for the user.
     *
     * @param caption     the pasted caption; null or blank when there is none
     * @param screenshots the uploaded screenshots' bytes; null or empty when there are none
     */
    public GenerateResultDto importPost(UUID userId, String caption, List<byte[]> screenshots) {
        // Step 1.
        UserPreferences stored = preferences.findById(userId).orElseThrow(NoPreferencesException::new);
        UserFoodProfile profile = UserFoodProfile.of(stored);

        // Step 2: counted after cleaning, so "\r\n" counts as one character, as in the browser.
        String cleaned = PromptBuilder.cleanCaption(caption);
        boolean hasCaption = !cleaned.isEmpty();
        List<byte[]> photos = screenshots == null ? List.of() : screenshots;
        if (cleaned.length() > MAX_CAPTION || photos.size() > MAX_SCREENSHOTS || (!hasCaption && photos.isEmpty())) {
            throw new PostImportRequestException(INVALID_POST_DETAIL);
        }
        List<AiImage> images = photos.isEmpty() ? List.of() : preparer.prepare(photos, MAX_SCREENSHOTS);

        // Step 3.
        OptionalInt used = usage.reserve(userId);
        if (used.isEmpty()) {
            throw new DailyLimitException();
        }
        List<DraftProblem> problems = new ArrayList<>();
        try {
            GeneratedRecipe row = createRecipe(userId, hasCaption ? cleaned : null, images, profile, problems);
            log.info("post import screenshots={} caption={} outcome=ok attempts={}", images.size(), hasCaption,
                    problems.size() + 1);
            return new GenerateResultDto(mapper.toDto(row), Math.max(0, dailyLimit - used.getAsInt()));
        } catch (RuntimeException e) {
            // The reasons may name an ingredient; nothing else about the post or the user is logged.
            log.info("post import screenshots={} caption={} outcome=failed reason={} rejected={} reasons={}",
                    images.size(), hasCaption, e.getClass().getSimpleName(), problems.size(),
                    String.join("; ", problems.stream().map(DraftProblem::reason).toList()));
            refundQuietly(userId);
            throw e;
        }
    }

    /** Steps 4 to 6. {@code problems} collects the rejected attempts' reasons for the caller's log line. */
    private GeneratedRecipe createRecipe(UUID userId, String caption, List<AiImage> images, UserFoodProfile profile,
                                         List<DraftProblem> problems) {
        Instant start = clock.instant();
        PostReading reading = images.isEmpty() ? null : read(images, start);
        RecipeAi writer = images.isEmpty() ? recipeAi : postAi;
        String postText = postText(caption, reading);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (timeLeft(start).compareTo(MIN_TIME_FOR_ATTEMPT) < 0) {
                log.info("post import stopped after {} attempt(s): under {} s of the budget left", attempt - 1,
                        MIN_TIME_FOR_ATTEMPT.toSeconds());
                break;
            }
            AiReply reply = writer.complete(prompts.postRecipe(caption, reading, profile, List.copyOf(problems)));

            PostReply parsed = replyParser.parse(reply.json());
            if (parsed.noDish()) {
                throw new NoDishException();
            }
            if (parsed.problem() != null) {
                problems.add(parsed.problem());
                continue;
            }
            RecipeDraft draft = parsed.draft();

            Optional<DraftProblem> problem = safety.check(draft, profile, Set.of(), SafetyCheck.Mode.ANY).problem()
                    .or(() -> swapProblem(parsed.post(), profile))
                    .or(() -> OwnWordsCheck.check(draft, postText));
            if (problem.isPresent()) {
                problems.add(problem.get());
                continue;
            }

            StoredRecipe recipe = mapper.withPost(mapper.fromDraft(draft, Set.of()), parsed.post());
            return save(userId, images.size(), caption != null, reply, recipe);
        }
        throw new GenerationFailedException();
    }

    /** Step 4: the reading, retried once if unreadable and 60 s of the 90 s budget remain. */
    private PostReading read(List<AiImage> images, Instant start) {
        for (int attempt = 1; attempt <= MAX_READING_ATTEMPTS; attempt++) {
            if (attempt > 1 && timeLeft(start).compareTo(MIN_TIME_FOR_READING_RETRY) < 0) {
                break;
            }
            AiReply reply = visionAi.complete(prompts.postReading(images));
            Optional<PostReading> parsed = readingParser.parse(reply.json());
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }
        throw new NoDishException();
    }

    /** A swap's replacement must itself be allowed; what it replaced is display text and is not checked. */
    private Optional<DraftProblem> swapProblem(StoredRecipe.PostNote post, UserFoodProfile profile) {
        for (StoredRecipe.Swap swap : post.swaps()) {
            if (!safety.allowed(swap.to(), profile)) {
                return Optional.of(new DraftProblem(
                        "The swap to " + swap.to() + " is not allowed. Choose a different replacement."));
            }
        }
        return Optional.empty();
    }

    /** What the own-words check compares against: the caption and the reading's text, one part per line. */
    private static String postText(String caption, PostReading reading) {
        List<String> parts = new ArrayList<>();
        if (caption != null) {
            parts.add(caption);
        }
        if (reading != null) {
            if (reading.dish() != null) {
                parts.add(reading.dish());
            }
            parts.add(reading.description());
            parts.addAll(reading.ingredients());
            parts.addAll(reading.steps());
        }
        return String.join("\n", parts);
    }

    private Duration timeLeft(Instant start) {
        return BUDGET.minus(Duration.between(start, clock.instant()));
    }

    /** The one short transaction: a single insert. The request document holds two counts, never the post. */
    private GeneratedRecipe save(UUID userId, int screenshots, boolean caption, AiReply reply, StoredRecipe recipe) {
        Map<String, Object> requestDocument = new LinkedHashMap<>();
        requestDocument.put("screenshots", screenshots);
        requestDocument.put("caption", caption);

        return transaction.execute(status -> {
            GeneratedRecipe row = GeneratedRecipe.builder()
                    .user(users.getReferenceById(userId))
                    .kind("post")
                    .mealType(null)
                    .title(recipe.title())
                    .recipe(mapper.toDocument(recipe))
                    .request(requestDocument)
                    .provider(reply.provider())
                    .model(reply.model())
                    .noveltyScore(BigDecimal.ZERO.setScale(3))
                    .referenceIds(new Long[0])
                    .build();
            return recipes.save(row);
        });
    }

    /** A failed refund must not hide the error that ended the import. */
    private void refundQuietly(UUID userId) {
        try {
            usage.refund(userId);
        } catch (RuntimeException e) {
            log.error("Could not refund an AI generation", e);
        }
    }
}
