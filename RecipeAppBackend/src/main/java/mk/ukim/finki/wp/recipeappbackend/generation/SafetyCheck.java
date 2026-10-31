package mk.ukim.finki.wp.recipeappbackend.generation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import mk.ukim.finki.wp.recipeappbackend.food.FoodRules;
import mk.ukim.finki.wp.recipeappbackend.food.IngredientNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The hard filter for AI recipes (spec section 5.3), enforced in code whatever the prompt said: no
 * ingredient, amount, title, description or step may hit one of the user's allergens, break one of their
 * diets or contain a disliked name as a whole word, and (outside {@link Mode#ANY}) the ingredients the user
 * did not list are limited. Pantry staples get no exemption from the safety rules.
 *
 * <p>Reasons in {@link DraftProblem} go back to the provider, so they name the ingredient but never say
 * "allergy" or "diet". {@link Conflict} reasons are for the user and do.
 */
@Component
public class SafetyCheck {

    /** How many ingredients that are neither the user's nor staples a recipe may add. */
    static final int MAX_EXTRAS = 3;

    public enum Mode {
        /** Only the user's ingredients and staples. */
        ONLY_MINE,
        /** The user's ingredients, staples and at most 3 others. */
        STAPLES_AND_EXTRAS,
        /** No coverage rule (today's meals). */
        ANY
    }

    /** {@code extras} are normalised names; empty in {@link Mode#ANY}. */
    public record SafetyResult(Optional<DraftProblem> problem, Set<String> extras) {
    }

    /** A requested ingredient the user cannot have; {@code reason} is "allergy: X", "diet: X" or "dislike". */
    public record Conflict(String ingredient, String reason) {
    }

    private final FoodRules rules;
    private final PantryStaples staples;

    @Autowired
    public SafetyCheck(FoodRules rules, PantryStaples staples) {
        this.rules = rules;
        this.staples = staples;
    }

    public SafetyResult check(RecipeDraft d, UserFoodProfile p, Set<String> userIngredients, Mode mode) {
        validate(p);
        // Names in free-text form ("Almonds, sliced" -> "almond sliced"); extras are reported in that form.
        List<String> names = new ArrayList<>();
        for (RecipeDraft.Ingredient ingredient : d.ingredients()) {
            String name = IngredientNames.normaliseText(ingredient.name());
            if (!name.isEmpty()) {
                names.add(name);
            }
            if (!reasonsFor(ingredient.name(), p).isEmpty()) {
                String shown = name.isEmpty() ? ingredient.name().strip() : name;
                return rejected("The recipe used " + shown + ", which is not allowed.", Set.of());
            }
        }
        for (RecipeDraft.Ingredient ingredient : d.ingredients()) {
            Hit hit = textHit(ingredient.amount(), p);
            if (hit != null) {
                return rejected(hit.sentence("The recipe text"), Set.of());
            }
        }
        for (String text : List.of(d.title(), d.description())) {
            Hit hit = textHit(text, p);
            if (hit != null) {
                return rejected(hit.sentence("The recipe text"), Set.of());
            }
        }
        for (String step : d.steps()) {
            Hit hit = textHit(step, p);
            if (hit != null) {
                return rejected(hit.sentence("A step"), Set.of());
            }
        }

        if (mode == Mode.ANY) {
            return new SafetyResult(Optional.empty(), Set.of());
        }

        Set<String> mine = new LinkedHashSet<>();
        for (String raw : userIngredients) {
            String name = IngredientNames.normaliseText(raw);
            if (!name.isEmpty()) {
                mine.add(name);
            }
        }
        Set<String> extras = new LinkedHashSet<>();
        for (String name : names) {
            boolean covered = staples.covers(name) || mine.stream().anyMatch(m -> covers(name, m));
            if (!covered) {
                extras.add(name);
            }
        }

        if (mode == Mode.ONLY_MINE && !extras.isEmpty()) {
            return rejected("The recipe used ingredients that were not in the list: " + String.join(", ", extras)
                    + ". Use only the listed ingredients and basic pantry items.", extras);
        }
        if (mode == Mode.STAPLES_AND_EXTRAS && extras.size() > MAX_EXTRAS) {
            return rejected("The recipe used too many ingredients that were not in the list: "
                    + String.join(", ", extras) + ". Use at most " + MAX_EXTRAS
                    + " of them, plus basic pantry items.", extras);
        }
        return new SafetyResult(Optional.empty(), extras);
    }

    /**
     * The requested ingredients the user cannot have, one {@link Conflict} per ingredient and reason, in the
     * order of {@code requested}. Names are in free-text form ({@link IngredientNames#normaliseText}), so
     * "almonds, chopped" is checked word by word; names with nothing left are ignored.
     */
    public List<Conflict> conflicts(Collection<String> requested, UserFoodProfile p) {
        validate(p);
        List<Conflict> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : requested) {
            String name = IngredientNames.normaliseText(raw);
            if (!name.isEmpty() && seen.add(name)) {
                for (String reason : reasonsFor(raw, p)) {
                    out.add(new Conflict(name, reason));
                }
            }
        }
        return out;
    }

    /** Fails closed: a profile key the rules do not know must never silently pass every recipe. */
    private void validate(UserFoodProfile p) {
        for (String key : p.allergens()) {
            if (!rules.allergenKeys().contains(key)) {
                throw new IllegalArgumentException("Unknown allergen: " + key);
            }
        }
        for (String key : p.diets()) {
            if (!rules.dietKeys().contains(key)) {
                throw new IllegalArgumentException("Unknown diet: " + key);
            }
        }
    }

    /**
     * The user-facing reasons ("allergy: Peanuts", "diet: Vegan", "dislike") an ingredient name breaks. The
     * name is checked in both forms, because the ingredient form glues punctuation and singularises only the
     * last word ("Almonds, sliced" would be "almonds sliced"): as an ingredient name and as free text.
     */
    private List<String> reasonsFor(String rawName, UserFoodProfile p) {
        Set<String> reasons = new LinkedHashSet<>();
        Optional<String> normalised = IngredientNames.normalise(rawName);
        if (normalised.isPresent()) {
            String name = normalised.get();
            for (String key : rules.allergensIn(List.of(name))) {
                if (p.allergens().contains(key)) {
                    reasons.add("allergy: " + rules.label(key));
                }
            }
            if (!p.diets().isEmpty()) {
                for (String key : rules.dietViolations(List.of(name), p.diets())) {
                    reasons.add("diet: " + rules.label(key));
                }
            }
            if (dislikedIn(" " + name + " ", p)) {
                reasons.add("dislike");
            }
        }
        String text = IngredientNames.normaliseText(rawName);
        if (!text.isEmpty()) {
            for (String key : rules.allergensInText(rawName)) {
                if (p.allergens().contains(key)) {
                    reasons.add("allergy: " + rules.label(key));
                }
            }
            if (!p.diets().isEmpty()) {
                for (String key : rules.dietViolationsInText(rawName, p.diets())) {
                    reasons.add("diet: " + rules.label(key));
                }
            }
            if (dislikedIn(" " + text + " ", p)) {
                reasons.add("dislike");
            }
        }
        return new ArrayList<>(reasons);
    }

    /** A rule hit in free text; {@code term} names it when it could be found, else null. */
    private record Hit(String term) {
        String sentence(String subject) {
            return term == null
                    ? subject + " used an ingredient that is not allowed."
                    : subject + " mentioned " + term + ", which is not allowed.";
        }
    }

    /** The first rule that the free text breaks (allergen, diet, dislike), or null. */
    private Hit textHit(String text, UserFoodProfile p) {
        String cleaned = IngredientNames.normaliseText(text);
        if (cleaned.isEmpty()) {
            return null;
        }
        String padded = " " + cleaned + " ";

        for (String key : rules.allergensInText(text)) {
            if (p.allergens().contains(key)) {
                return new Hit(termIn(padded, rules.excludedTerms(Set.of(key), Set.of())));
            }
        }
        if (!p.diets().isEmpty()) {
            for (String key : rules.dietViolationsInText(text, p.diets())) {
                return new Hit(termIn(padded, rules.excludedTerms(Set.of(), Set.of(key))));
            }
        }
        for (String dislike : p.dislikes()) {
            if (hasWord(padded, dislike) || hasWord(padded, IngredientNames.normaliseWords(dislike))) {
                return new Hit(dislike);
            }
        }
        return null;
    }

    /**
     * Whether a disliked name is in {@code padded}: the stored form (an {@code IngredientNames.normalise}
     * result) or its free-text form, which differ for multi-word names ("brussels sprout" / "brussel sprout").
     */
    private static boolean dislikedIn(String padded, UserFoodProfile p) {
        for (String dislike : p.dislikes()) {
            if (hasWord(padded, dislike) || hasWord(padded, IngredientNames.normaliseWords(dislike))) {
                return true;
            }
        }
        return false;
    }

    /** The first of {@code terms} that is a whole word (or phrase) in {@code padded}, or null. */
    private static String termIn(String padded, Collection<String> terms) {
        for (String term : terms) {
            if (hasWord(padded, term)) {
                return term;
            }
        }
        return null;
    }

    private static boolean hasWord(String padded, String phrase) {
        return !phrase.isEmpty() && padded.contains(" " + phrase + " ");
    }

    /** Two names cover each other when one contains the other as whole words. */
    private static boolean covers(String a, String b) {
        String pa = " " + a + " ";
        String pb = " " + b + " ";
        return pa.contains(pb) || pb.contains(pa);
    }

    private static SafetyResult rejected(String reason, Set<String> extras) {
        return new SafetyResult(Optional.of(new DraftProblem(reason)), extras);
    }
}
