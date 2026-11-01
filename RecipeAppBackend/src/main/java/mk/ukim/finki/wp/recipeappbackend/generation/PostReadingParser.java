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
 * are not strings, are blank or are too long are dropped. A missing or null part reads as empty. Malformed
 * JSON, a root that is not an object, a wrongly typed part, or too many list items is "unreadable" (an empty
 * result). It never throws and never puts the reply in a message.
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
        if (!isAbsent(dishNode) && !dishNode.isString() || !isAbsent(description) && !description.isString()
                || !isListOfAtMost(ingredients, MAX_INGREDIENTS) || !isListOfAtMost(steps, MAX_STEPS)) {
            return Optional.empty();
        }
        String dish = isAbsent(dishNode) ? null : dishNode.asString().strip();
        if (dish != null && (dish.isEmpty() || dish.length() > MAX_DISH_LENGTH)) {
            dish = null;
        }
        return Optional.of(new PostReading(dish, isAbsent(description) ? "" : truncated(description.asString()),
                strings(ingredients, MAX_INGREDIENT_LENGTH), strings(steps, MAX_STEP_LENGTH)));
    }

    /** A missing or explicit-null part reads as empty. */
    private static boolean isAbsent(JsonNode node) {
        return node == null || node.isNull();
    }

    /** A missing or null list is empty; a present value must be an array of at most {@code max} items. */
    private static boolean isListOfAtMost(JsonNode node, int max) {
        return isAbsent(node) || node.isArray() && node.size() <= max;
    }

    /** Stripped and cut to 300 characters without splitting a surrogate pair, then stripped again. */
    private static String truncated(String raw) {
        String text = raw.strip();
        if (text.length() <= MAX_DESCRIPTION_LENGTH) {
            return text;
        }
        int end = MAX_DESCRIPTION_LENGTH;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end).strip();
    }

    /** The trimmed string items of at most {@code maxLength} characters; the rest are dropped. */
    private static List<String> strings(JsonNode array, int maxLength) {
        List<String> items = new ArrayList<>();
        if (isAbsent(array)) {
            return items;
        }
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
