package mk.ukim.finki.wp.recipeappbackend.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PostReadingParserTest {

    private final PostReadingParser parser = new PostReadingParser();

    private static String listOf(int count) {
        return IntStream.range(0, count).mapToObj(i -> "\"item " + i + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String json(String dish, String description, String ingredients, String steps) {
        return "{\"dish\":" + dish + ",\"description\":" + description + ",\"ingredients\":" + ingredients
                + ",\"steps\":" + steps + "}";
    }

    @Test
    void valid_isParsed() {
        String reply = json("\" Charred Corn Salad \"", "\"Corn with feta.\"",
                "[\"2 ears of corn\",\" 100 g feta \"]", "[\"Char the corn.\",\"Crumble the feta on top.\"]");
        assertThat(parser.parse(reply)).contains(new PostReading("Charred Corn Salad", "Corn with feta.",
                List.of("2 ears of corn", "100 g feta"), List.of("Char the corn.", "Crumble the feta on top.")));
    }

    @Test
    void nullDish_isAllowed() {
        assertThat(parser.parse(json("null", "\"A plate of food.\"", "[]", "[]")))
                .contains(new PostReading(null, "A plate of food.", List.of(), List.of()));
        assertThat(parser.parse("{\"description\":\"d\",\"ingredients\":[],\"steps\":[]}"))
                .contains(new PostReading(null, "d", List.of(), List.of()));
    }

    @Test
    void fortyIngredients_allowed_fortyOne_unreadable() {
        assertThat(parser.parse(json("\"x\"", "\"d\"", listOf(40), "[]")))
                .hasValueSatisfying(r -> assertThat(r.ingredients()).hasSize(40));
        assertThat(parser.parse(json("\"x\"", "\"d\"", listOf(41), "[]"))).isEmpty();
    }

    @Test
    void thirtySteps_allowed_thirtyOne_unreadable() {
        assertThat(parser.parse(json("\"x\"", "\"d\"", "[]", listOf(30))))
                .hasValueSatisfying(r -> assertThat(r.steps()).hasSize(30));
        assertThat(parser.parse(json("\"x\"", "\"d\"", "[]", listOf(31)))).isEmpty();
    }

    @Test
    void tooLongItems_areDropped() {
        String ingredient120 = "a".repeat(120);
        String ingredient121 = "a".repeat(121);
        String step600 = "b".repeat(600);
        String step601 = "b".repeat(601);
        String reply = json("\"x\"", "\"d\"", "[\"" + ingredient121 + "\",\"" + ingredient120 + "\",\"\",\"  \"]",
                "[\"" + step601 + "\",\"" + step600 + "\"]");
        assertThat(parser.parse(reply)).contains(new PostReading("x", "d", List.of(ingredient120), List.of(step600)));
    }

    @Test
    void nonStringItems_areDropped() {
        assertThat(parser.parse(json("\"x\"", "\"d\"", "[\"egg\",5,null,{\"a\":1},[\"x\"],true,\"milk\"]", "[]")))
                .hasValueSatisfying(r -> assertThat(r.ingredients()).containsExactly("egg", "milk"));
    }

    @Test
    void tooLongDish_isNull() {
        assertThat(parser.parse(json("\"" + "d".repeat(120) + "\"", "\"d\"", "[]", "[]")))
                .hasValueSatisfying(r -> assertThat(r.dish()).hasSize(120));
        assertThat(parser.parse(json("\"" + "d".repeat(121) + "\"", "\"d\"", "[]", "[]")))
                .hasValueSatisfying(r -> assertThat(r.dish()).isNull());
        assertThat(parser.parse(json("\"   \"", "\"d\"", "[]", "[]")))
                .hasValueSatisfying(r -> assertThat(r.dish()).isNull());
    }

    @Test
    void descriptionOver300_isTruncatedTo300() {
        assertThat(parser.parse(json("\"x\"", "\"" + "c".repeat(300) + "\"", "[]", "[]")))
                .hasValueSatisfying(r -> assertThat(r.description()).isEqualTo("c".repeat(300)));
        assertThat(parser.parse(json("\"x\"", "\"" + "c".repeat(301) + "\"", "[]", "[]")))
                .hasValueSatisfying(r -> assertThat(r.description()).isEqualTo("c".repeat(300)));
    }

    @Test
    void malformedJson_and_wrongTypes_areUnreadable() {
        assertThat(parser.parse("{\"dish\":\"x\",\"ingredients\":[")).isEqualTo(Optional.empty());
        assertThat(parser.parse("not json")).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("[\"egg\"]")).isEmpty();
        assertThat(parser.parse(json("\"x\"", "\"d\"", "\"egg\"", "[]"))).isEmpty();
        assertThat(parser.parse(json("\"x\"", "\"d\"", "[]", "\"mix\""))).isEmpty();
        assertThat(parser.parse(json("5", "\"d\"", "[]", "[]"))).isEmpty();
        assertThat(parser.parse(json("\"x\"", "7", "[]", "[]"))).isEmpty();
    }

    @Test
    void missingRequiredParts_areUnreadable() {
        assertThat(parser.parse("{}")).isEmpty();
        assertThat(parser.parse("{\"dish\":\"x\",\"ingredients\":[],\"steps\":[]}")).isEmpty();
        assertThat(parser.parse("{\"dish\":\"x\",\"description\":\"d\",\"steps\":[]}")).isEmpty();
        assertThat(parser.parse("{\"dish\":\"x\",\"description\":\"d\",\"ingredients\":[]}")).isEmpty();
        assertThat(parser.parse(json("\"x\"", "null", "[]", "[]"))).isEmpty();
    }
}
