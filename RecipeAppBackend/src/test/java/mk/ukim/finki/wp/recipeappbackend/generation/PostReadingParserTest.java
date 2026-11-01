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
    void missingParts_areEmpty() {
        PostReading none = new PostReading(null, "", List.of(), List.of());
        assertThat(parser.parse("{}")).contains(none);
        assertThat(parser.parse(json("null", "null", "null", "null"))).contains(none);
        // Each of the three parts missing, one at a time.
        assertThat(parser.parse("{\"dish\":\"x\",\"ingredients\":[\"egg\"],\"steps\":[\"Fry.\"]}"))
                .contains(new PostReading("x", "", List.of("egg"), List.of("Fry.")));
        assertThat(parser.parse("{\"dish\":\"x\",\"description\":\"d\",\"steps\":[\"Fry.\"]}"))
                .contains(new PostReading("x", "d", List.of(), List.of("Fry.")));
        assertThat(parser.parse("{\"dish\":\"x\",\"description\":\"d\",\"ingredients\":[\"egg\"]}"))
                .contains(new PostReading("x", "d", List.of("egg"), List.of()));
        // Each of the three parts explicitly null, one at a time.
        assertThat(parser.parse(json("\"x\"", "null", "[\"egg\"]", "[\"Fry.\"]")))
                .contains(new PostReading("x", "", List.of("egg"), List.of("Fry.")));
        assertThat(parser.parse(json("\"x\"", "\"d\"", "null", "[\"Fry.\"]")))
                .contains(new PostReading("x", "d", List.of(), List.of("Fry.")));
        assertThat(parser.parse(json("\"x\"", "\"d\"", "[\"egg\"]", "null")))
                .contains(new PostReading("x", "d", List.of("egg"), List.of()));
    }

    @Test
    void descriptionCutInsideAnEmoji_isCutBeforeIt() {
        String emoji = new String(Character.toChars(0x1F355));
        // 299 letters then a 2-char emoji straddling positions 299-300: the pair is dropped whole.
        assertThat(parser.parse(json("\"x\"", "\"" + "c".repeat(299) + emoji + "tail\"", "[]", "[]")))
                .hasValueSatisfying(r -> assertThat(r.description()).isEqualTo("c".repeat(299)));
        // An emoji ending exactly at 300 stays.
        assertThat(parser.parse(json("\"x\"", "\"" + "c".repeat(298) + emoji + "tail\"", "[]", "[]")))
                .hasValueSatisfying(r -> assertThat(r.description()).isEqualTo("c".repeat(298) + emoji));
        // Stripped again after the cut.
        assertThat(parser.parse(json("\"x\"", "\"" + "c".repeat(297) + "   tail\"", "[]", "[]")))
                .hasValueSatisfying(r -> assertThat(r.description()).isEqualTo("c".repeat(297)));
    }
}
