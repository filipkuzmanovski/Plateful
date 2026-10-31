package mk.ukim.finki.wp.recipeappbackend.generation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.food.IngredientNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the AI's JSON reply into a {@link RecipeDraft} and checks its structure (spec section 5.3). It never
 * throws: anything wrong (not JSON, a code fence, prose, truncated text, wrong types, nulls, out-of-range
 * values) becomes a {@link DraftProblem} whose reason is fit for the retry prompt.
 */
@Component
public class DraftParser {

    static final String UNREADABLE = "unreadable reply";

    private static final int MAX_TITLE = 120;
    private static final int MAX_DESCRIPTION = 300;
    private static final int MIN_SERVINGS = 1;
    private static final int MAX_SERVINGS = 12;
    private static final int MAX_MINUTES = 600;
    private static final int MIN_INGREDIENTS = 3;
    private static final int MAX_INGREDIENTS = 20;
    private static final int MAX_INGREDIENT_NAME = 60;
    private static final int MAX_AMOUNT = 120;
    private static final int MIN_STEPS = 2;
    private static final int MAX_STEPS = 15;
    private static final int MAX_STEP = 600;

    /** Exactly one of the two fields is non-null. */
    public record Result(RecipeDraft draft, DraftProblem problem) {
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final CuisineCatalog cuisines;

    @Autowired
    public DraftParser(CuisineCatalog cuisines) {
        this.cuisines = cuisines;
    }

    /** Parses one recipe object. */
    public Result parse(String json) {
        JsonNode root = readObject(json);
        if (root == null) {
            return problem(UNREADABLE);
        }
        return parseRecipe(root);
    }

    /** Parses an object {@code {meal: recipe, ...}} into one result per requested meal key. */
    public Map<String, Result> parseMeals(String json, Set<String> meals) {
        Map<String, Result> results = new LinkedHashMap<>();
        JsonNode root = readObject(json);
        for (String meal : meals) {
            if (root == null) {
                results.put(meal, problem(UNREADABLE));
                continue;
            }
            JsonNode node = root.get(meal);
            if (node == null || node.isNull()) {
                results.put(meal, problem("The reply had no recipe for " + meal + "."));
            } else if (!node.isObject()) {
                results.put(meal, problem("The " + meal + " recipe was not a JSON object."));
            } else {
                results.put(meal, parseRecipe(node));
            }
        }
        return results;
    }

    private static JsonNode readObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            return root != null && root.isObject() ? root : null;
        } catch (Exception | StackOverflowError e) {
            return null;
        }
    }

    private Result parseRecipe(JsonNode n) {
        try {
            return check(n);
        } catch (RuntimeException e) {
            return problem(UNREADABLE);
        }
    }

    private Result check(JsonNode n) {
        JsonNode titleNode = n.get("title");
        if (!isText(titleNode) || titleNode.asString().isBlank()) {
            return problem("The title was missing.");
        }
        String title = titleNode.asString().strip();
        if (title.length() > MAX_TITLE) {
            return problem("The title was longer than " + MAX_TITLE + " characters.");
        }

        JsonNode descriptionNode = n.get("description");
        if (!isText(descriptionNode)) {
            return problem("The description was missing.");
        }
        String description = descriptionNode.asString().strip();
        if (description.length() > MAX_DESCRIPTION) {
            return problem("The description was longer than " + MAX_DESCRIPTION + " characters.");
        }

        Integer servings = integer(n.get("servings"));
        if (servings == null) {
            return problem("The number of servings was missing or not a whole number.");
        }
        if (servings < MIN_SERVINGS || servings > MAX_SERVINGS) {
            return problem("The number of servings must be between " + MIN_SERVINGS + " and " + MAX_SERVINGS + ".");
        }
        Integer prep = integer(n.get("prepMinutes"));
        if (prep == null || prep < 0 || prep > MAX_MINUTES) {
            return problem("The prep time must be a whole number of minutes from 0 to " + MAX_MINUTES + ".");
        }
        Integer cook = integer(n.get("cookMinutes"));
        if (cook == null || cook < 0 || cook > MAX_MINUTES) {
            return problem("The cook time must be a whole number of minutes from 0 to " + MAX_MINUTES + ".");
        }

        JsonNode cuisineNode = n.get("cuisine");
        String cuisine = null;
        if (isText(cuisineNode)) {
            String key = CuisineCatalog.normalize(cuisineNode.asString());
            cuisine = !key.isEmpty() && cuisines.contains(key) ? key : null;
        }

        JsonNode ingredientsNode = n.get("ingredients");
        if (ingredientsNode == null || !ingredientsNode.isArray()) {
            return problem("The ingredients were missing.");
        }
        int ingredientCount = ingredientsNode.size();
        if (ingredientCount < MIN_INGREDIENTS || ingredientCount > MAX_INGREDIENTS) {
            return problem("The recipe must have between " + MIN_INGREDIENTS + " and " + MAX_INGREDIENTS
                    + " ingredients.");
        }
        List<RecipeDraft.Ingredient> ingredients = new ArrayList<>();
        for (JsonNode item : ingredientsNode) {
            if (item == null || !item.isObject()) {
                return problem("An ingredient was not an object with a name and an amount.");
            }
            JsonNode nameNode = item.get("name");
            if (!isText(nameNode) || nameNode.asString().isBlank()) {
                return problem("An ingredient had no name.");
            }
            String name = nameNode.asString().strip();
            if (name.length() > MAX_INGREDIENT_NAME) {
                return problem("An ingredient name was longer than " + MAX_INGREDIENT_NAME + " characters.");
            }
            if (IngredientNames.normalise(name).isEmpty()) {
                return problem("The ingredient name '" + name + "' was not a real ingredient name.");
            }
            JsonNode amountNode = item.get("amount");
            if (!isText(amountNode)) {
                return problem("The ingredient '" + name + "' had no amount.");
            }
            String amount = amountNode.asString().strip();
            if (amount.length() > MAX_AMOUNT) {
                return problem("An ingredient amount was longer than " + MAX_AMOUNT + " characters.");
            }
            ingredients.add(new RecipeDraft.Ingredient(name, amount));
        }

        JsonNode stepsNode = n.get("steps");
        if (stepsNode == null || !stepsNode.isArray()) {
            return problem("The steps were missing.");
        }
        int stepCount = stepsNode.size();
        if (stepCount < MIN_STEPS || stepCount > MAX_STEPS) {
            return problem("The recipe must have between " + MIN_STEPS + " and " + MAX_STEPS + " steps.");
        }
        List<String> steps = new ArrayList<>();
        for (JsonNode item : stepsNode) {
            if (!isText(item) || item.asString().isBlank()) {
                return problem("A step was empty or not text.");
            }
            String step = item.asString().strip();
            if (step.length() > MAX_STEP) {
                return problem("A step was longer than " + MAX_STEP + " characters.");
            }
            steps.add(step);
        }

        return new Result(
                new RecipeDraft(title, description, servings, prep, cook, cuisine,
                        List.copyOf(ingredients), List.copyOf(steps)),
                null);
    }

    private static boolean isText(JsonNode node) {
        return node != null && node.isString();
    }

    /** The value of a JSON whole number that fits an int; null for anything else (text, null, 2.5, huge). */
    private static Integer integer(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            return null;
        }
        return node.intValue();
    }

    private static Result problem(String reason) {
        return new Result(null, new DraftProblem(reason));
    }
}
