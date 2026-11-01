package mk.ukim.finki.wp.recipeappbackend.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the vision model's reply to a post-screenshot reading (spec section 4.2): an object with a {@code dish}
 * (null or 1 to 120 characters), a {@code description} (cut to 300 characters), {@code ingredients} (at most 40
 * strings of 1 to 120 characters) and {@code steps} (at most 30 strings of 1 to 600 characters).
 *
 * <p>A {@code dish} that is too long becomes null, a long {@code description} is truncated, and list items that
 * are not strings, are blank or are too long are dropped. Malformed JSON, a root that is not an object, a
 * missing or wrongly typed part, or too many list items is "unreadable" (an empty result). It never throws and
 * never puts the reply in a message.
 */
@Component
public class PostReadingParser {

    static final int MAX_DISH_LENGTH = 120;
    static final int MAX_DESCRIPTION_LENGTH = 300;
    static final int MAX_INGREDIENTS = 40;
    static final int MAX_INGREDIENT_LENGTH = 120;
    static final int MAX_STEPS = 30;
    static final int MAX_STEP_LENGTH = 600;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The reading; empty when the reply is unreadable. */
    public Optional<PostReading> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception | StackOverflowError e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        JsonNode dishNode = root.get("dish");
        JsonNode description = root.get("description");
        JsonNode ingredients = root.get("ingredients");
        JsonNode steps = root.get("steps");
        boolean dishOk = dishNode == null || dishNode.isNull() || dishNode.isString();
        if (!dishOk || description == null || !description.isString()
                || !isListOfAtMost(ingredients, MAX_INGREDIENTS) || !isListOfAtMost(steps, MAX_STEPS)) {
            return Optional.empty();
        }
        String dish = dishNode == null || dishNode.isNull() ? null : dishNode.asString().strip();
        if (dish != null && (dish.isEmpty() || dish.length() > MAX_DISH_LENGTH)) {
            dish = null;
        }
        String text = description.asString().strip();
        if (text.length() > MAX_DESCRIPTION_LENGTH) {
            text = text.substring(0, MAX_DESCRIPTION_LENGTH);
        }
        return Optional.of(new PostReading(dish, text, strings(ingredients, MAX_INGREDIENT_LENGTH),
                strings(steps, MAX_STEP_LENGTH)));
    }

    private static boolean isListOfAtMost(JsonNode node, int max) {
        return node != null && node.isArray() && node.size() <= max;
    }

    /** The trimmed string items of at most {@code maxLength} characters; the rest are dropped. */
    private static List<String> strings(JsonNode array, int maxLength) {
        List<String> items = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isString()) {
                continue;
            }
            String value = item.asString().strip();
            if (!value.isEmpty() && value.length() <= maxLength) {
                items.add(value);
            }
        }
        return items;
    }
}
