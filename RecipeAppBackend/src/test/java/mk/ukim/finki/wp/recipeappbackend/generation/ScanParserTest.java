package mk.ukim.finki.wp.recipeappbackend.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ScanParserTest {

    private final ScanParser parser = new ScanParser();

    private static String arrayOf(int count) {
        return IntStream.range(0, count).mapToObj(i -> "\"item " + i + "\"")
                .collect(Collectors.joining(",", "{\"ingredients\":[", "]}"));
    }

    @Test
    void valid() {
        assertThat(parser.parse("{\"ingredients\":[\"egg\",\" spinach \",\"red bell pepper\"]}"))
                .contains(List.of("egg", "spinach", "red bell pepper"));
    }

    @Test
    void emptyArrayIsValid() {
        assertThat(parser.parse("{\"ingredients\":[]}")).contains(List.of());
    }

    @Test
    void fortyAllowed_fortyOneUnreadable() {
        assertThat(parser.parse(arrayOf(40))).hasValueSatisfying(list -> assertThat(list).hasSize(40));
        assertThat(parser.parse(arrayOf(41))).isEmpty();
    }

    @Test
    void tooLongEntryDropped() {
        String sixty = "a".repeat(60);
        String sixtyOne = "a".repeat(61);
        assertThat(parser.parse("{\"ingredients\":[\"egg\",\"" + sixtyOne + "\",\"" + sixty + "\"]}"))
                .contains(List.of("egg", sixty));
        // Length is judged after trimming.
        assertThat(parser.parse("{\"ingredients\":[\"  " + sixty + "  \"]}")).contains(List.of(sixty));
    }

    @Test
    void blankEntryDropped() {
        assertThat(parser.parse("{\"ingredients\":[\"\",\"  \",\"egg\"]}")).contains(List.of("egg"));
    }

    @Test
    void malformedJsonUnreadable() {
        assertThat(parser.parse("{\"ingredients\":[\"egg\"")).isEqualTo(Optional.empty());
        assertThat(parser.parse("not json")).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("[\"egg\"]")).isEmpty();
    }

    @Test
    void missingArrayUnreadable() {
        assertThat(parser.parse("{}")).isEmpty();
        assertThat(parser.parse("{\"ingredients\":null}")).isEmpty();
        assertThat(parser.parse("{\"ingredients\":\"egg\"}")).isEmpty();
        assertThat(parser.parse("{\"items\":[\"egg\"]}")).isEmpty();
    }

    @Test
    void nonStringEntryDropped() {
        assertThat(parser.parse("{\"ingredients\":[\"egg\",5,null,{\"a\":1},[\"x\"],true,\"milk\"]}"))
                .contains(List.of("egg", "milk"));
    }
}
