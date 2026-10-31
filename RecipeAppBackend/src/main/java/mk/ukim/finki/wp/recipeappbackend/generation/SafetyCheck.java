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
 * ingredient or step may hit one of the user's allergens, break one of their diets or contain a disliked
 * name as a whole word, and (outside {@link Mode#ANY}) the ingredients the user did not list are limited.
 * Pantry staples get no exemption from the safety rules.
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
        List<String> names = new ArrayList<>();
        for (RecipeDraft.Ingredient ingredient : d.ingredients()) {
            IngredientNames.normalise(ingredient.name()).ifPresent(names::add);
        }

        for (String name : names) {
            if (!conflictsOf(name, p).isEmpty()) {
                return rejected("The recipe used " + name + ", which is not allowed.", Set.of());
            }
        }
        for (String step : d.steps()) {
            String offender = stepOffender(step, p);
            if (offender != null) {
                return rejected(offender, Set.of());
            }
        }

        if (mode == Mode.ANY) {
            return new SafetyResult(Optional.empty(), Set.of());
        }

        Set<String> covering = new LinkedHashSet<>();
        for (String mine : userIngredients) {
            IngredientNames.normalise(mine).ifPresent(covering::add);
        }
        covering.addAll(staples.names());
        Set<String> extras = new LinkedHashSet<>();
        for (String name : names) {
            if (covering.stream().noneMatch(c -> covers(name, c))) {
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
     * order of {@code requested}. Names are normalised; names that normalise to nothing are ignored.
     */
    public List<Conflict> conflicts(Collection<String> requested, UserFoodProfile p) {
        List<Conflict> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : requested) {
            IngredientNames.normalise(raw).ifPresent(name -> {
                if (seen.add(name)) {
                    for (String reason : conflictsOf(name, p)) {
                        out.add(new Conflict(name, reason));
                    }
                }
            });
        }
        return out;
    }

    /** The user-facing reasons ("allergy: Peanuts", "diet: Vegan", "dislike") a normalised name breaks. */
    private List<String> conflictsOf(String name, UserFoodProfile p) {
        List<String> reasons = new ArrayList<>();
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
        String padded = " " + name + " ";
        if (p.dislikes().stream().anyMatch(dislike -> hasWord(padded, dislike))) {
            reasons.add("dislike");
        }
        return reasons;
    }

    /** A retry-prompt sentence when the step text breaks a rule, else null. */
    private String stepOffender(String step, UserFoodProfile p) {
        String text = IngredientNames.normaliseText(step);
        if (text.isEmpty()) {
            return null;
        }
        String padded = " " + text + " ";

        Set<String> allergens = new LinkedHashSet<>(rules.allergensInText(step));
        allergens.retainAll(p.allergens());
        if (!allergens.isEmpty()) {
            String key = allergens.iterator().next();
            return stepSentence(termIn(padded, rules.excludedTerms(Set.of(key), Set.of())));
        }
        if (!p.diets().isEmpty()) {
            Set<String> broken = rules.dietViolationsInText(step, p.diets());
            if (!broken.isEmpty()) {
                String key = broken.iterator().next();
                return stepSentence(termIn(padded, rules.excludedTerms(Set.of(), Set.of(key))));
            }
        }
        for (String dislike : p.dislikes()) {
            if (hasWord(padded, dislike)) {
                return stepSentence(dislike);
            }
        }
        return null;
    }

    private static String stepSentence(String term) {
        return term == null
                ? "A step used an ingredient that is not allowed."
                : "A step used " + term + ", which is not allowed.";
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
        return padded.contains(" " + phrase + " ");
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
