package mk.ukim.finki.wp.recipeappbackend.cuisine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CuisineCatalogTest {

    private final CuisineCatalog catalog = new CuisineCatalog();

    @Test
    void loadsEveryRowWithUniqueKeys() {
        assertThat(catalog.all()).hasSizeGreaterThanOrEqualTo(190);
        assertThat(catalog.all()).extracting(CuisineEntry::key).doesNotHaveDuplicates()
                .allMatch(k -> k.equals(CuisineCatalog.normalize(k)));
    }

    @Test
    void containsAllSpoonacularCuisines() {
        List.of("african", "american", "asian", "british", "cajun", "caribbean", "chinese", "eastern european", "european",
                        "french", "german", "greek", "indian", "irish", "italian", "japanese", "jewish", "korean", "latin american",
                        "mediterranean", "mexican", "middle eastern", "nordic", "southern", "spanish", "thai", "vietnamese")
                .forEach(k -> assertThat(catalog.contains(k)).as(k).isTrue());
    }

    @Test
    void macedonianIsEuropean() {
        assertThat(catalog.find("macedonian")).get()
                .extracting(CuisineEntry::label, CuisineEntry::region).containsExactly("Macedonian", Region.EUROPE);
    }

    @Test
    void mediterraneanRegionSpansContinents() {
        assertThat(catalog.keysForRegion("mediterranean"))
                .contains("mediterranean", "italian", "lebanese", "moroccan").doesNotContain("german");
    }

    @Test
    void unknownRegionIsEmpty() {
        assertThat(catalog.keysForRegion("atlantis")).isEmpty();
    }

    @Test
    void normalizeTrimsLowercasesAndCollapses() {
        assertThat(CuisineCatalog.normalize("  Middle   Eastern ")).isEqualTo("middle eastern");
        assertThat(CuisineCatalog.normalize("middle\teastern")).isEqualTo("middle eastern");
        assertThat(CuisineCatalog.normalize(null)).isEqualTo("");
    }

    @Test
    void resolveFilterNoInputMeansNoFilter() {
        assertThat(catalog.resolveFilter(null, List.of())).isEmpty();
    }

    @Test
    void resolveFilterUnionsRegionsAndCuisines() {
        Set<String> keys = catalog.resolveFilter(List.of(" Italian "), List.of("asia")).orElseThrow();
        assertThat(keys).contains("italian", "chinese", "japanese", "asian").doesNotContain("mexican");
    }

    @Test
    void resolveFilterOnlyUnknownMeansMatchNothing() {
        assertThat(catalog.resolveFilter(List.of("lol"), List.of("atlantis"))).hasValue(Set.of());
    }

    @Test
    void normalizeAndValidateDedupesAndRejectsUnknown() {
        assertThat(catalog.normalizeAndValidate(List.of("  Italian ", "ITALIAN", "Greek"))).containsExactly("italian", "greek");
        assertThatThrownBy(() -> catalog.normalizeAndValidate(List.of("italian", "lol")))
                .isInstanceOf(InvalidCuisineException.class).hasMessageContaining("lol");
    }
}
