package mk.ukim.finki.wp.recipeappbackend.generation;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the AI's reply to a post-recipe writing request (spec section 4.2): an {@code origin} (recipe, dish or
 * none), the ingredient {@code swaps}, and the recipe fields, which {@link DraftParser} reads and checks.
 *
 * <p>{@code origin = none} means the post shows no dish; the recipe fields are then not checked. Swaps are 0 to
 * 10 objects whose {@code from} and {@code to} are each 1 to 60 characters after trimming: bad items are
 * dropped and only the first ten good ones are kept. It never throws and never puts the reply in a message.
 */
@Component
public class PostReplyParser {

    static final String BAD_ORIGIN = "The origin must be recipe, dish or none.";
    static final int MAX_SWAPS = 10;
    static final int MAX_SWAP_SIDE = 60;

    private static final String NONE = "none";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final DraftParser draftParser;

    @Autowired
    public PostReplyParser(DraftParser draftParser) {
        this.draftParser = draftParser;
    }

    /** Parses one reply. */
    public PostReply parse(String json) {
        JsonNode root = readObject(json);
        if (root == null) {
            return problem(DraftParser.UNREADABLE);
        }
        JsonNode originNode = root.get("origin");
        String origin = originNode != null && originNode.isString() ? originNode.asString() : null;
        if (NONE.equals(origin)) {
            return new PostReply(true, null, null, null);
        }
        if (!"recipe".equals(origin) && !"dish".equals(origin)) {
            return problem(BAD_ORIGIN);
        }
        DraftParser.Result result = draftParser.parse(json);
        if (result.draft() == null) {
            return new PostReply(false, null, null, result.problem());
        }
        return new PostReply(false, result.draft(), new StoredRecipe.PostNote(origin, swaps(root.get("swaps"))),
                null);
    }

    private static List<StoredRecipe.Swap> swaps(JsonNode node) {
        List<StoredRecipe.Swap> swaps = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return swaps;
        }
        for (JsonNode item : node) {
            if (swaps.size() == MAX_SWAPS) {
                break;
            }
            if (item == null || !item.isObject()) {
                continue;
            }
            String from = side(item.get("from"));
            String to = side(item.get("to"));
            if (from != null && to != null) {
                swaps.add(new StoredRecipe.Swap(from, to));
            }
        }
        return swaps;
    }

    /** The trimmed text of 1 to 60 characters; null for anything else. */
    private static String side(JsonNode node) {
        if (node == null || !node.isString()) {
            return null;
        }
        String text = node.asString().strip();
        return text.isEmpty() || text.length() > MAX_SWAP_SIDE ? null : text;
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

    private static PostReply problem(String reason) {
        return new PostReply(false, null, null, new DraftProblem(reason));
    }
}
