package mk.ukim.finki.wp.recipeappbackend.generation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The JSON Schema the AI's reply must follow (spec sections 5.3 and 6.3). Its field names are exactly the ones
 * {@link DraftParser} reads, and its bounds are the structure check's bounds.
 *
 * <p>The schema is written for the intersection of Groq's strict structured outputs and Gemini's
 * {@code responseJsonSchema}: every object has {@code additionalProperties: false} and lists every property
 * in {@code required}, a nullable field uses a type array, and only the keywords {@code type},
 * {@code properties}, {@code required}, {@code items}, {@code minItems}, {@code maxItems}, {@code minLength},
 * {@code maxLength}, {@code minimum}, {@code maximum} and {@code additionalProperties} appear.
 */
public final class RecipeSchema {

    /** The meal keys, in the order they appear in a daily schema and prompt. */
    static final List<String> MEALS = List.of("breakfast", "lunch", "dinner");

    private RecipeSchema() {
    }

    /** The schema of one recipe. */
    public static Map<String, Object> single() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", string(1, 120));
        props.put("description", string(0, 300));
        props.put("servings", integer(1, 12));
        props.put("prepMinutes", integer(0, 600));
        props.put("cookMinutes", integer(0, 600));
        Map<String, Object> cuisine = new LinkedHashMap<>();
        cuisine.put("type", List.of("string", "null"));
        props.put("cuisine", cuisine);
        props.put("ingredients", array(ingredient(), 3, 20));
        props.put("steps", array(string(1, 600), 2, 15));
        return object(props);
    }

    /** The schema of an object with one recipe per requested meal (only breakfast, lunch, dinner), in that order. */
    public static Map<String, Object> meals(Set<String> meals) {
        if (meals == null || meals.isEmpty()) {
            throw new IllegalArgumentException("At least one meal is required");
        }
        for (String meal : meals) {
            if (!MEALS.contains(meal)) {
                throw new IllegalArgumentException("Unknown meal: " + meal);
            }
        }
        Map<String, Object> props = new LinkedHashMap<>();
        for (String meal : MEALS) {
            if (meals.contains(meal)) {
                props.put(meal, single());
            }
        }
        return object(props);
    }

    private static Map<String, Object> ingredient() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", string(1, 60));
        props.put("amount", string(0, 120));
        return object(props);
    }

    private static Map<String, Object> object(Map<String, Object> props) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("type", "object");
        o.put("properties", props);
        o.put("required", new ArrayList<>(props.keySet()));
        o.put("additionalProperties", false);
        return o;
    }

    private static Map<String, Object> array(Map<String, Object> items, int min, int max) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", "array");
        a.put("items", items);
        a.put("minItems", min);
        a.put("maxItems", max);
        return a;
    }

    private static Map<String, Object> string(int min, int max) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "string");
        if (min > 0) {
            s.put("minLength", min);
        }
        s.put("maxLength", max);
        return s;
    }

    private static Map<String, Object> integer(int min, int max) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("type", "integer");
        i.put("minimum", min);
        i.put("maximum", max);
        return i;
    }
}
