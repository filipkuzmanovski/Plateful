package mk.ukim.finki.wp.recipeappbackend.food;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IngredientNamesTest {

    private static final Path CASES = Path.of("..", "tools", "dataset", "normalise-cases.csv");

    static Stream<Arguments> sharedCases() throws IOException {
        List<List<String>> rows = parseCsv(Files.readString(CASES, StandardCharsets.UTF_8));
        // rows.get(0) is the header: input,expected
        return rows.stream().skip(1).map(r -> Arguments.of(r.get(0), r.get(1)));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> \"{1}\"")
    @MethodSource("sharedCases")
    void matchesTheSharedCases(String input, String expected) {
        Optional<String> expectedValue = expected.isEmpty() ? Optional.empty() : Optional.of(expected);
        assertThat(IngredientNames.normalise(input)).isEqualTo(expectedValue);
    }

    @Test
    void sharedCasesFileIsReadCompletely() throws IOException {
        List<List<String>> rows = parseCsv(Files.readString(CASES, StandardCharsets.UTF_8));
        assertThat(rows.get(0)).containsExactly("input", "expected");
        assertThat(rows).hasSizeGreaterThan(20);
        assertThat(rows).allSatisfy(r -> assertThat(r).hasSize(2));
        // the quoted cells survive intact: a literal tab, and leading/trailing spaces
        assertThat(rows).anySatisfy(r -> assertThat(r.get(0)).contains("\t"));
        assertThat(rows).anySatisfy(r -> assertThat(r.get(0)).isEqualTo("  salt   & pepper "));
    }

    @Test
    void nullInputHasNoName() {
        assertThat(IngredientNames.normalise(null)).isEmpty();
    }

    @Test
    void normaliseWordsSingularisesEveryWord() {
        assertThat(IngredientNames.normaliseWords("Serve with Peanuts and crème-fraîche!"))
                .isEqualTo("serve with peanut and creme fraiche");
        assertThat(IngredientNames.normaliseWords("Fluffy Pancakes with Berries"))
                .isEqualTo("fluffy pancake with berry");
    }

    @Test
    void normaliseWordsIsEmptyNotNullWhenNothingIsLeft() {
        assertThat(IngredientNames.normaliseWords(null)).isEmpty();
        assertThat(IngredientNames.normaliseWords("!!!")).isEmpty();
    }

    @Test
    void normaliseWordsHasNoLengthCapAndDropsLoneS() {
        assertThat(IngredientNames.normaliseWords("a".repeat(61))).isEqualTo("a".repeat(61));
        assertThat(IngredientNames.normaliseWords("a s b")).isEqualTo("a b");
    }

    @Test
    void normaliseTextTurnsPunctuationIntoWordBreaks() {
        assertThat(IngredientNames.normaliseText("Chef's peanuts/almonds")).isEqualTo("chef peanut almond");
        assertThat(IngredientNames.normaliseText("milk,butter")).isEqualTo("milk butter");
        assertThat(IngredientNames.normaliseText("and/or")).isEqualTo("and or");
        assertThat(IngredientNames.normaliseText("salt\u2014pepper")).isEqualTo("salt pepper");
        assertThat(IngredientNames.normaliseText("Serve with crème-fraîche!")).isEqualTo("serve with creme fraiche");
        assertThat(IngredientNames.normaliseText("2tbsp(30g)oil")).isEqualTo("tbsp g oil");
    }

    @Test
    void normaliseTextIsEmptyNotNullWhenNothingIsLeft() {
        assertThat(IngredientNames.normaliseText(null)).isEmpty();
        assertThat(IngredientNames.normaliseText("1/2 - 3!")).isEmpty();
    }

    /** Minimal quote-aware CSV reader: "" escapes a quote, and quoted cells may hold commas or newlines. */
    static List<List<String>> parseCsv(String text) {
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean pending = false; // something has been read for the current row
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                pending = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
                pending = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (pending) {
                    row.add(cell.toString());
                    rows.add(row);
                }
                row = new ArrayList<>();
                cell.setLength(0);
                pending = false;
            } else {
                cell.append(c);
                pending = true;
            }
        }
        if (pending) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
