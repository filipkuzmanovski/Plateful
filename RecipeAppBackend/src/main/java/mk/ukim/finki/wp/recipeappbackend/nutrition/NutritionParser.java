package mk.ukim.finki.wp.recipeappbackend.nutrition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the AI's {@code {"items":[...]}} reply. It never throws: anything unexpected is an empty result. */
public final class NutritionParser {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private NutritionParser() {
    }

    public static Optional<List<NutritionItem>> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                return Optional.empty();
            }
            JsonNode items = root.get("items");
            if (items == null || !items.isArray()) {
                return Optional.empty();
            }
            List<NutritionItem> result = new ArrayList<>();
            for (JsonNode n : items) {
                JsonNode line = n.get("line");
                if (!n.isObject() || line == null || !line.isIntegralNumber() || !line.canConvertToInt()) {
                    return Optional.empty();
                }
                Double grams = number(n, "grams");
                Double calories = number(n, "calories");
                Double protein = number(n, "protein");
                Double fat = number(n, "fat");
                Double carbs = number(n, "carbs");
                if (grams == null || calories == null || protein == null || fat == null || carbs == null) {
                    return Optional.empty();
                }
                result.add(new NutritionItem(line.intValue(), grams, calories, protein, fat, carbs));
            }
            return Optional.of(result);
        } catch (Exception | StackOverflowError e) {
            return Optional.empty();
        }
    }

    private static Double number(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isNumber() ? v.doubleValue() : null;
    }
}
