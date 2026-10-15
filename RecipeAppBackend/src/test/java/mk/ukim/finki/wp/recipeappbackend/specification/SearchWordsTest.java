package mk.ukim.finki.wp.recipeappbackend.specification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchWordsTest {

    @Test
    void nullAndBlankGiveNoWords() {
        assertThat(RecipeSpecifications.searchWords(null)).isEmpty();
        assertThat(RecipeSpecifications.searchWords("  \t ")).isEmpty();
    }

    @Test
    void controlCharactersAreStripped() {
        assertThat(RecipeSpecifications.searchWords("a\u0000b")).containsExactly("ab");
        assertThat(RecipeSpecifications.searchWords("\u0000\u0007")).isEmpty();
    }

    @Test
    void tabsAndNewlinesStillSeparateWords() {
        assertThat(RecipeSpecifications.searchWords("garlic\tsoup\nbread")).containsExactly("garlic", "soup", "bread");
    }

    @Test
    void repeatedWordsAreKeptOnce() {
        assertThat(RecipeSpecifications.searchWords("garlic garlic")).containsExactly("garlic");
        assertThat(RecipeSpecifications.searchWords("Garlic GARLIC garlic")).containsExactly("garlic");
    }

    @Test
    void atMostEightDistinctWordsAreKept() {
        assertThat(RecipeSpecifications.searchWords("a1 b2 c3 d4 e5 f6 g7 h8 i9 j10 k11 l12"))
                .containsExactly("a1", "b2", "c3", "d4", "e5", "f6", "g7", "h8");
    }

    @Test
    void theQueryIsCappedAt100Characters() {
        String q = "x".repeat(150);
        assertThat(RecipeSpecifications.searchWords(q)).containsExactly("x".repeat(100));
    }
}
