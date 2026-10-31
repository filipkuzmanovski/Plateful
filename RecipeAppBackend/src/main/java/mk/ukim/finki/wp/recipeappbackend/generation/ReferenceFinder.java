package mk.ukim.finki.wp.recipeappbackend.generation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mk.ukim.finki.wp.recipeappbackend.food.IngredientNames;
import mk.ukim.finki.wp.recipeappbackend.model.projection.Pairing;
import mk.ukim.finki.wp.recipeappbackend.model.projection.ReferenceQuery;
import mk.ukim.finki.wp.recipeappbackend.model.projection.ReferenceRecipe;
import mk.ukim.finki.wp.recipeappbackend.repository.PlatefulComparisonRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.ReferenceDataRepository;
import org.springframework.stereotype.Component;

/**
 * Finds the RecipeNLG reference recipes that inspire the AI, the ingredient pairing facts for the prompt, and
 * the recipes a draft is compared with (spec sections 3.2 and 5.4).
 *
 * <p>The dataset's allergen and diet tags were frozen at import (and computed with a normaliser that glues
 * punctuation, so "1 c. peanuts/almonds" may be mis-tagged), so they are only a pre-filter. Every candidate is
 * re-checked here with the current rules on its ingredient names and on every ingredient line, exactly as
 * {@link SafetyCheck} checks a raw ingredient name, and dropped on any hit.
 */
@Component
public class ReferenceFinder {

    /** Candidates fetched from the dataset before the Java re-check. */
    static final int CANDIDATES = 20;
    /** Names whose pairings are looked up. */
    static final int MAX_PAIRING_NAMES = 5;
    /** Partners kept per name. */
    static final int MAX_PARTNERS = 8;
    /** Partners fetched per name, so that skipped (forbidden) ones can be replaced. */
    static final int PARTNER_FETCH = 3 * MAX_PARTNERS;
    /** Closest dataset recipes added to the compared set. */
    static final int COMPARED_DATASET = 10;
    /** Approved Plateful recipes added to the compared set. */
    static final int COMPARED_PLATEFUL = 20;

    private final ReferenceDataRepository reference;
    private final PlatefulComparisonRepository plateful;
    private final SafetyCheck safety;

    public ReferenceFinder(ReferenceDataRepository reference, PlatefulComparisonRepository plateful,
                           SafetyCheck safety) {
        this.reference = reference;
        this.plateful = plateful;
        this.safety = safety;
    }

    /**
     * Up to {@code keep} dataset recipes sharing the most of the {@code wanted} names, for the meal and the
     * user's filters, that pass the Java re-check, best match first.
     */
    public List<ReferenceRecipe> inspiration(Set<String> wanted, String mealType, UserFoodProfile p, int keep) {
        List<ReferenceRecipe> candidates = reference.findClosest(new ReferenceQuery(
                wanted, mealType, p.allergens(), p.diets(), p.dislikes(), CANDIDATES));
        return List.copyOf(take(candidates, p, keep, new ArrayList<>()));
    }

    /**
     * Up to {@code keep} recipes for one meal of today's meals: first those that use the user's liked
     * ingredients, then random ones for the meal to make up the number. All pass the Java re-check.
     */
    public List<ReferenceRecipe> dailyInspiration(String mealType, UserFoodProfile p, int keep) {
        List<ReferenceRecipe> result = new ArrayList<>();
        if (!p.likedIngredients().isEmpty()) {
            result.addAll(inspiration(p.likedIngredients(), mealType, p, keep));
        }
        if (result.size() < keep) {
            List<ReferenceRecipe> random = reference.randomForMeal(
                    mealType, p.allergens(), p.diets(), p.dislikes(), CANDIDATES);
            take(random, p, keep, result);
        }
        return List.copyOf(result);
    }

    /**
     * For up to 5 of {@code names}, the top 8 partners the user may eat, with the share of recipes that pair
     * them. A name with no partners is left out. Partners are checked as ingredient names.
     */
    public Map<String, List<Pairing>> pairings(Collection<String> names, UserFoodProfile p) {
        Map<String, List<Pairing>> result = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String name : names) {
            if (result.size() >= MAX_PAIRING_NAMES) {
                break;
            }
            if (name == null || !seen.add(name)) {
                continue;
            }
            List<Pairing> allowed = new ArrayList<>();
            for (Pairing pairing : reference.pairings(name, PARTNER_FETCH)) {
                if (allowed.size() >= MAX_PARTNERS) {
                    break;
                }
                if (safety.allowed(pairing.partner(), p)) {
                    allowed.add(pairing);
                }
            }
            if (!allowed.isEmpty()) {
                result.put(name, List.copyOf(allowed));
            }
        }
        return result;
    }

    /**
     * What the novelty check compares {@code d} with: the examples, the 10 dataset recipes closest to the
     * draft's ingredients (no filters, as this is only about similarity), and the 20 approved Plateful recipes
     * closest to them. Duplicates are removed.
     */
    public List<ComparedRecipe> comparisonSet(RecipeDraft d, List<ReferenceRecipe> examples) {
        Set<String> names = new LinkedHashSet<>();
        for (RecipeDraft.Ingredient ingredient : d.ingredients()) {
            IngredientNames.normalise(ingredient.name()).ifPresent(names::add);
        }

        Set<ComparedRecipe> compared = new LinkedHashSet<>();
        for (ReferenceRecipe example : examples) {
            compared.add(compared(example));
        }
        for (ReferenceRecipe closest : reference.findClosest(new ReferenceQuery(
                names, null, Set.of(), Set.of(), Set.of(), COMPARED_DATASET))) {
            compared.add(compared(closest));
        }
        if (!names.isEmpty()) {
            compared.addAll(plateful.closestApproved(names, COMPARED_PLATEFUL));
        }
        return List.copyOf(compared);
    }

    private static ComparedRecipe compared(ReferenceRecipe r) {
        return new ComparedRecipe(r.title(), new LinkedHashSet<>(r.ingredientNames()));
    }

    /** Adds to {@code into} (until it holds {@code keep}) the candidates that pass the re-check and are new. */
    private List<ReferenceRecipe> take(List<ReferenceRecipe> candidates, UserFoodProfile p, int keep,
                                       List<ReferenceRecipe> into) {
        Set<Long> have = new LinkedHashSet<>();
        into.forEach(r -> have.add(r.id()));
        for (ReferenceRecipe candidate : candidates) {
            if (into.size() >= keep) {
                break;
            }
            if (!have.contains(candidate.id()) && passes(candidate, p)) {
                into.add(candidate);
                have.add(candidate.id());
            }
        }
        return into;
    }

    /** The Java re-check: the ingredient names and every ingredient line must break none of the user's rules. */
    private boolean passes(ReferenceRecipe r, UserFoodProfile p) {
        for (String name : r.ingredientNames()) {
            if (!safety.allowed(name, p)) {
                return false;
            }
        }
        for (String line : r.ingredientLines()) {
            if (!safety.allowed(line, p)) {
                return false;
            }
        }
        return true;
    }
}
