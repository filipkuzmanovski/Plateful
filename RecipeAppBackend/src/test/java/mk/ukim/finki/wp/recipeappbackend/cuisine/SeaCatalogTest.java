package mk.ukim.finki.wp.recipeappbackend.cuisine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SeaCatalogTest {

    private final SeaCatalog catalog = new SeaCatalog();

    @Test
    void fiveSeasInFileOrder() {
        assertThat(catalog.all()).extracting(Sea::key)
                .containsExactly("mediterranean", "caribbean", "north_sea", "pacific", "gulf_of_mexico");
    }

    @Test
    void northSeaTextVerbatim() {
        Sea north = catalog.all().stream().filter(s -> s.key().equals("north_sea")).findFirst().orElseThrow();
        assertThat(north.vibe()).isEqualTo("Comforting, smoky, pickled, and hearty.");
        assertThat(north.starIngredients()).isEqualTo("Cod, haddock, herring, langoustines.");
        assertThat(north.iconDishes()).isEqualTo("British Fish & Chips, Scandinavian Pickled Herring.");
    }

    @Test
    void pacificLabel() {
        Sea pacific = catalog.all().stream().filter(s -> s.key().equals("pacific")).findFirst().orElseThrow();
        assertThat(pacific.label()).isEqualTo("Sea of Japan & Pacific");
    }
}
