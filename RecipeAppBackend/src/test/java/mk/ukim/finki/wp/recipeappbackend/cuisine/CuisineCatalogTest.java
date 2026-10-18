package mk.ukim.finki.wp.recipeappbackend.cuisine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CuisineCatalogTest {

    private static final String NBSP = String.valueOf((char) 0x00A0);

    private final CuisineCatalog catalog = new CuisineCatalog();

    @Test
    void loadsEveryRowWithUniqueKeys() {
        assertThat(catalog.all()).hasSizeGreaterThanOrEqualTo(190);
        assertThat(catalog.all()).extracting(CuisineEntry::key).doesNotHaveDuplicates()
                .allMatch(k -> k.equals(CuisineCatalog.normalize(k)));
    }

    // Kept after the importer was removed: the imported recipes in the DB still use these cuisine keys.
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
        assertThat(CuisineCatalog.normalize("middle" + NBSP + "eastern")).isEqualTo("middle eastern");
        assertThat(CuisineCatalog.normalize(NBSP + "Italian" + NBSP)).isEqualTo("italian");
        assertThat(CuisineCatalog.normalize(null)).isEqualTo("");
    }

    @Test
    void resolveFilterNoInputMeansNoFilter() {
        assertThat(catalog.resolveFilter(null, List.of(), null)).isEmpty();
    }

    @Test
    void resolveFilterUnionsRegionsAndCuisines() {
        Set<String> keys = catalog.resolveFilter(List.of(" Italian "), List.of("asia"), null).orElseThrow();
        assertThat(keys).contains("italian", "chinese", "japanese", "asian").doesNotContain("mexican");
    }

    @Test
    void resolveFilterOnlyUnknownMeansMatchNothing() {
        assertThat(catalog.resolveFilter(List.of("lol"), List.of("atlantis"), null)).hasValue(Set.of());
    }

    @Test
    void normalizeAndValidateDedupesAndRejectsUnknown() {
        assertThat(catalog.normalizeAndValidate(List.of("  Italian ", "ITALIAN", "Greek"))).containsExactly("italian", "greek");
        assertThatThrownBy(() -> catalog.normalizeAndValidate(List.of("italian", "lol")))
                .isInstanceOf(InvalidCuisineException.class).hasMessageContaining("lol");
    }

    @Test
    void seasColumnParses() {
        assertThat(catalog.find("french").orElseThrow().seas()).containsExactly("mediterranean", "north_sea");
        assertThat(catalog.find("mexican").orElseThrow().seas()).containsExactly("pacific", "gulf_of_mexico");
        assertThat(catalog.find("swiss").orElseThrow().seas()).isEmpty();
    }

    @Test
    void keysForSeaCaribbean() {
        assertThat(catalog.keysForSea("caribbean")).contains("jamaican", "cuban").doesNotContain("british");
    }

    @Test
    void mediterraneanSeaMatchesOldFlagRows() {
        assertThat(catalog.keysForSea("mediterranean")).contains("greek", "lebanese", "moroccan", "italian");
    }

    @Test
    void regionMediterraneanIsAliasForSea() {
        assertThat(catalog.resolveFilter(null, List.of("mediterranean"), null))
                .isEqualTo(Optional.of(catalog.keysForSea("mediterranean")));
    }

    @Test
    void resolveUnionOfAllThree() {
        Set<String> keys = catalog.resolveFilter(List.of("thai"), List.of("africa"), List.of("north_sea")).orElseThrow();
        assertThat(keys).contains("thai", "moroccan", "british");
    }

    @Test
    void onlyUnknownSeaMatchesNothing() {
        assertThat(catalog.resolveFilter(null, null, List.of("atlantis"))).isEqualTo(Optional.of(Set.of()));
    }

    @Test
    void unknownSeaKeyInCsvFailsStartup() {
        assertThatThrownBy(() -> CuisineCatalog.parseLine("x,X,europe,lake", 2))
                .isInstanceOf(IllegalStateException.class);
    }
}
