package mk.ukim.finki.wp.recipeappbackend.generation;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import mk.ukim.finki.wp.recipeappbackend.food.IngredientNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Rejects an AI recipe that is a copy of a known one (spec section 5.4). A draft is too close to a compared
 * recipe when the Jaccard similarity of their ingredient names (pantry staples removed from both) is at least
 * {@link #INGREDIENT_THRESHOLD}, or the Jaccard similarity of their title words (stop words removed) is at
 * least {@link #TITLE_THRESHOLD}. The ingredient test is skipped for a pair when either set is empty after
 * removing staples; the title test likewise when either title has no words left.
 */
@Component
public class NoveltyCheck {

    public static final double INGREDIENT_THRESHOLD = 0.60;
    public static final double TITLE_THRESHOLD = 0.75;

    public static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "the", "with", "of", "in", "on", "for",
            "style", "easy", "quick", "simple", "best", "homemade", "recipe", "my");

    private static final Pattern HEALTH_WORDS = Pattern.compile("allerg|diet", Pattern.CASE_INSENSITIVE);

    /** {@code score} is the highest ingredient similarity against any compared recipe (0 if none). */
    public record NoveltyResult(double score, Optional<DraftProblem> problem) {
    }

    private final PantryStaples staples;

    @Autowired
    public NoveltyCheck(PantryStaples staples) {
        this.staples = staples;
    }

    public NoveltyResult check(RecipeDraft d, List<ComparedRecipe> compared) {
        Set<String> draftNames = new LinkedHashSet<>();
        for (RecipeDraft.Ingredient ingredient : d.ingredients()) {
            IngredientNames.normalise(ingredient.name()).ifPresent(draftNames::add);
        }
        draftNames.removeIf(staples::covers);
        Set<String> draftTitle = titleWords(d.title());

        double bestScore = 0.0;
        double closeness = -1.0;
        String closestTitle = null;
        for (ComparedRecipe c : compared) {
            Set<String> names = new LinkedHashSet<>(c.names());
            names.removeIf(staples::covers);
            double ingredientSimilarity = jaccard(draftNames, names);
            double titleSimilarity = jaccard(draftTitle, titleWords(c.title()));
            bestScore = Math.max(bestScore, ingredientSimilarity);

            boolean tooClose = ingredientSimilarity >= INGREDIENT_THRESHOLD || titleSimilarity >= TITLE_THRESHOLD;
            double pairCloseness = Math.max(ingredientSimilarity, titleSimilarity);
            if (tooClose && pairCloseness > closeness) {
                closeness = pairCloseness;
                closestTitle = c.title();
            }
        }

        Optional<DraftProblem> problem = closestTitle == null
                ? Optional.empty()
                : Optional.of(new DraftProblem(similarTo(closestTitle)));
        return new NoveltyResult(bestScore, problem);
    }

    /**
     * The retry reason. It goes back to the provider, which must never see the words for allergies or diets,
     * so a dataset title containing them ("Allergy-Friendly Muffins") is not quoted.
     */
    static String similarTo(String title) {
        if (HEALTH_WORDS.matcher(title).find()) {
            return "Too similar to an existing recipe. Make something more different.";
        }
        return "Too similar to '" + title + "'. Make something more different.";
    }

    private static Set<String> titleWords(String title) {
        Set<String> words = new LinkedHashSet<>();
        String text = IngredientNames.normaliseText(title);
        if (!text.isEmpty()) {
            words.addAll(Arrays.asList(text.split(" ")));
        }
        words.removeAll(STOP_WORDS);
        return words;
    }

    /** |a and b| / |a or b|; 0 (test skipped) when either set is empty. */
    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        int shared = 0;
        for (String item : a) {
            if (b.contains(item)) {
                shared++;
            }
        }
        int union = a.size() + b.size() - shared;
        return (double) shared / union;
    }
}
