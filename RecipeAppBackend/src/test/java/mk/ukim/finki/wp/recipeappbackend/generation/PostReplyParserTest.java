package mk.ukim.finki.wp.recipeappbackend.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import org.junit.jupiter.api.Test;

class PostReplyParserTest {

    private final PostReplyParser parser = new PostReplyParser(new DraftParser(new CuisineCatalog()));

    private static final String RECIPE_FIELDS = "\"title\":\"Tomato Pasta\",\"description\":\"A tasty dish.\","
            + "\"servings\":4,\"prepMinutes\":10,\"cookMinutes\":20,\"cuisine\":null,"
            + "\"ingredients\":[{\"name\":\"pasta\",\"amount\":\"200 g\"},{\"name\":\"tomato\",\"amount\":\"3\"},"
            + "{\"name\":\"basil\",\"amount\":\"1 handful\"}],\"steps\":[\"Boil the pasta.\",\"Add the sauce.\"]";

    private static String reply(String origin, String swaps) {
        return "{\"origin\":" + origin + ",\"swaps\":" + swaps + "," + RECIPE_FIELDS + "}";
    }

    private static String swap(String from, String to) {
        return "{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
    }

    @Test
    void recipeOrigin_handsOverToDraftParser() {
        PostReply r = parser.parse(reply("\"recipe\"", "[" + swap("butter", "olive oil") + "]"));
        assertThat(r.noDish()).isFalse();
        assertThat(r.problem()).isNull();
        assertThat(r.draft().title()).isEqualTo("Tomato Pasta");
        assertThat(r.draft().ingredients()).hasSize(3);
        assertThat(r.post().origin()).isEqualTo("recipe");
        assertThat(r.post().swaps()).containsExactly(new StoredRecipe.Swap("butter", "olive oil"));
    }

    @Test
    void dishOrigin_handsOverToDraftParser() {
        PostReply r = parser.parse(reply("\"dish\"", "[]"));
        assertThat(r.noDish()).isFalse();
        assertThat(r.problem()).isNull();
        assertThat(r.draft().steps()).hasSize(2);
        assertThat(r.post().origin()).isEqualTo("dish");
        assertThat(r.post().swaps()).isEmpty();
    }

    @Test
    void noneOrigin_isNoDish_withoutCheckingRecipeFields() {
        PostReply r = parser.parse("{\"origin\":\"none\",\"title\":\"\"}");
        assertThat(r.noDish()).isTrue();
        assertThat(r.draft()).isNull();
        assertThat(r.post()).isNull();
        assertThat(r.problem()).isNull();
    }

    @Test
    void missingOrUnknownOrigin_isAProblem() {
        for (String json : new String[] {
                "{" + RECIPE_FIELDS + ",\"swaps\":[]}",
                reply("\"video\"", "[]"),
                reply("null", "[]"),
                reply("3", "[]"),
                reply("\"RECIPE\"", "[]")}) {
            PostReply r = parser.parse(json);
            assertThat(r.noDish()).isFalse();
            assertThat(r.draft()).isNull();
            assertThat(r.problem().reason()).isEqualTo("The origin must be recipe, dish or none.");
        }
    }

    @Test
    void draftProblem_isPassedOn() {
        String bad = "{\"origin\":\"recipe\",\"swaps\":[],"
                + RECIPE_FIELDS.replace("\"servings\":4", "\"servings\":0") + "}";
        PostReply r = parser.parse(bad);
        assertThat(r.noDish()).isFalse();
        assertThat(r.draft()).isNull();
        assertThat(r.post()).isNull();
        assertThat(r.problem().reason()).contains("servings");
    }

    @Test
    void swaps_overTen_keepFirstTen_badItemsDropped() {
        String items = IntStream.rangeClosed(1, 13)
                .mapToObj(i -> swap("from" + i, "to" + i))
                .collect(Collectors.joining(","));
        PostReply many = parser.parse(reply("\"recipe\"", "[" + items + "]"));
        assertThat(many.post().swaps()).hasSize(10);
        assertThat(many.post().swaps().get(0)).isEqualTo(new StoredRecipe.Swap("from1", "to1"));
        assertThat(many.post().swaps().get(9)).isEqualTo(new StoredRecipe.Swap("from10", "to10"));

        String mixed = "[" + swap("a", "  ") + "," + swap("x".repeat(61), "ok") + ",\"text\",5,{\"from\":\"b\"},"
                + swap("  c  ", " d ") + "," + swap("y".repeat(60), "z") + "]";
        PostReply r = parser.parse(reply("\"dish\"", mixed));
        assertThat(r.post().swaps()).containsExactly(new StoredRecipe.Swap("c", "d"),
                new StoredRecipe.Swap("y".repeat(60), "z"));
    }

    @Test
    void swapsMissingOrNotAnArray_areEmpty() {
        String noSwaps = "{\"origin\":\"recipe\"," + RECIPE_FIELDS + "}";
        assertThat(parser.parse(noSwaps).post().swaps()).isEmpty();
        assertThat(parser.parse(reply("\"recipe\"", "\"none\"")).post().swaps()).isEmpty();
    }

    @Test
    void unreadableJson_isAProblem() {
        for (String json : new String[] {null, "", "  ", "not json", "[1,2]", "{\"origin\":\"rec", "\"recipe\""}) {
            PostReply r = parser.parse(json);
            assertThat(r.noDish()).isFalse();
            assertThat(r.draft()).isNull();
            assertThat(r.problem().reason()).isEqualTo(DraftParser.UNREADABLE);
        }
    }
}
