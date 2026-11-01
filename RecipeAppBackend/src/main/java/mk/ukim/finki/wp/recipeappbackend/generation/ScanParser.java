package mk.ukim.finki.wp.recipeappbackend.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the vision model's reply to a photo scan: an object with an {@code ingredients} array of at most 40
 * entries (spec section 4.2). Entries that are not strings, are blank or are over 60 characters after
 * trimming are dropped rather than failing the reply. Anything else is "unreadable" (an empty result);
 * it never throws.
 */
@Component
public class ScanParser {

    static final int MAX_ENTRIES = 40;
    static final int MAX_NAME_LENGTH = 60;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The trimmed names, in order; empty when the reply is unreadable. An empty list is a valid answer. */
    public Optional<List<String>> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        JsonNode array;
        try {
            JsonNode root = MAPPER.readTree(json);
            array = root == null || !root.isObject() ? null : root.get("ingredients");
        } catch (Exception | StackOverflowError e) {
            return Optional.empty();
        }
        if (array == null || !array.isArray() || array.size() > MAX_ENTRIES) {
            return Optional.empty();
        }
        List<String> names = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isString()) {
                continue;
            }
            String name = item.asString().strip();
            if (!name.isEmpty() && name.length() <= MAX_NAME_LENGTH) {
                names.add(name);
            }
        }
        return Optional.of(names);
    }
}
